package com.chickengamer.circuitbender.core

import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.random.Random

/** How a bent line is tied: left alone, to VCC, or to GND. */
enum class LineMode { OFF, HIGH, LOW }

/** Two data lines bridged by a patch wire: wired-AND or wired-OR. */
enum class BridgeMode { OFF, AND, OR }

/** Which pixel the ISP believes the RGGB colour filter starts on. */
enum class BayerPhase(val dx: Int, val dy: Int) { NONE(0, 0), SHIFT_X(1, 0), SHIFT_Y(0, 1), SHIFT_XY(1, 1) }

/** Corrupted sensor config-bus (SCCB/I2C) registers: a mode that sticks until reboot. */
enum class SccbGlitch { NONE, NEGATIVE, NEON, COLOR_BARS, MIRROR }

/** Interlace faults on analog-out cameras. */
enum class FieldError { NONE, SWAP, DROP }

/**
 * One data line D0-D7 between sensor and DSP. [pot] is the resistance in
 * series with the short: 0 = hard short (bit always forced), 1 = open
 * (no effect); in between the line sits near the logic threshold and the bit
 * is forced only some of the time (docs/RESEARCH.md 1.3).
 */
data class DataLine(val mode: LineMode = LineMode.OFF, val pot: Float = 0f) {
    val active: Boolean get() = mode != LineMode.OFF && pot < 1f
}

/** Every bend point on the rack. Defaults are a stock, unmodified camera. */
data class BendSettings(
    val dataLines: List<DataLine> = List(8) { DataLine() },
    val bridge: BridgeMode = BridgeMode.OFF,
    val bridgeA: Int = 6,
    val bridgeB: Int = 7,
    /** Crossed patch cables: lines [crossA] and [crossB] swapped (off when equal). */
    val crossA: Int = 0,
    val crossB: Int = 0,
    /** Data line XOR-ed with a clock-derived square wave (-1 = off). */
    val crosstalkBit: Int = -1,
    /** The square wave toggles every 2^period samples. */
    val crosstalkPeriod: Int = 1,
    /** DSP latches the YUV422 stream one byte late. */
    val bytePhaseSlip: Boolean = false,
    /** Pot on PCLK: no effect below the knee, then lines start losing clocks. */
    val pixelClockPot: Float = 0f,
    /** Lost HSYNC: the stream is re-wrapped at width + this many samples. */
    val strideOffset: Int = 0,
    /** Lost VSYNC: rows the picture rolls per frame. */
    val vsyncRoll: Float = 0f,
    /** Frame-buffer address line A[n] (byte address). */
    val addressBit: Int = 12,
    val addressMode: LineMode = LineMode.OFF,
    val bayerPhase: BayerPhase = BayerPhase.NONE,
    /** Pot on the CCD output / ADC reference: analog gain, 1 = untouched. */
    val adcGain: Float = 1f,
    val sccb: SccbGlitch = SccbGlitch.NONE,
    /** 0..1 bit-error rate in the JPEG entropy stream. */
    val jpegErrors: Float = 0f,
    val fieldError: FieldError = FieldError.NONE,
    /** 0..1 supply sag: freezes, half-written frames, dimming. */
    val brownout: Float = 0f,
) {
    internal val busActive: Boolean
        get() = dataLines.any { it.active } || bridge != BridgeMode.OFF || crossA != crossB ||
            crosstalkBit in 0..7 || bytePhaseSlip
    internal val timingActive: Boolean get() = pixelClockPot > PCLK_KNEE || strideOffset != 0
    internal val addressActive: Boolean get() = addressMode != LineMode.OFF

    val isNeutral: Boolean
        get() = !busActive && !timingActive && !addressActive && vsyncRoll == 0f &&
            bayerPhase == BayerPhase.NONE && adcGain == 1f && sccb == SccbGlitch.NONE &&
            jpegErrors <= 0f && fieldError == FieldError.NONE && brownout <= 0f

    companion object {
        /** Below this the RC-rounded clock edges still cross the logic threshold. */
        const val PCLK_KNEE = 0.5f
    }
}

/** Operations on the sensor's YUV422 byte stream (Y0 U Y1 V ...). */
object Bus {
    fun applyDataLines(bytes: ByteArray, lines: List<DataLine>, rnd: Random, from: Int = 0, to: Int = bytes.size) {
        for ((bit, line) in lines.withIndex()) {
            if (!line.active || bit > 7) continue
            val mask = 1 shl bit
            val p = 1f - line.pot.coerceIn(0f, 1f)
            val high = line.mode == LineMode.HIGH
            // Chance per byte as a 16-bit threshold; one random word covers two bytes.
            val threshold = (p * 65536f).toInt()
            var bits = 0
            for (i in from until to) {
                if (i and 1 == 0 || i == from) bits = rnd.nextInt()
                val draw = if (i and 1 == 0) bits and 0xFFFF else bits ushr 16
                if (p >= 1f || draw < threshold) {
                    val b = bytes[i].toInt()
                    bytes[i] = (if (high) b or mask else b and mask.inv()).toByte()
                }
            }
        }
    }

    fun bridge(bytes: ByteArray, a: Int, b: Int, mode: BridgeMode) {
        if (mode == BridgeMode.OFF || a == b) return
        val ma = 1 shl a
        val mb = 1 shl b
        val both = ma or mb
        for (i in bytes.indices) {
            val v = bytes[i].toInt()
            val ba = v and ma != 0
            val bb = v and mb != 0
            val on = if (mode == BridgeMode.AND) ba && bb else ba || bb
            bytes[i] = (if (on) v or both else v and both.inv()).toByte()
        }
    }

    fun swap(bytes: ByteArray, a: Int, b: Int) {
        if (a == b) return
        for (i in bytes.indices) {
            val v = bytes[i].toInt()
            val ba = (v shr a) and 1
            val bb = (v shr b) and 1
            if (ba != bb) bytes[i] = (v xor ((1 shl a) or (1 shl b))).toByte()
        }
    }

    /** XOR a data line with a square wave derived from the sample clock. */
    fun crosstalk(bytes: ByteArray, lineBytes: Int, bit: Int, period: Int) {
        val mask = 1 shl bit
        for (i in bytes.indices) {
            val sample = (i % lineBytes) / 2
            if ((sample shr period) and 1 == 1) bytes[i] = (bytes[i].toInt() xor mask).toByte()
        }
    }

    /** The DSP latches one byte late: Y reads U, U reads Y, and so on. */
    fun slipByte(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        val first = bytes[0]
        System.arraycopy(bytes, 1, bytes, 0, bytes.size - 1)
        bytes[bytes.size - 1] = first
    }

    private fun clamp(v: Int) = if (v < 0) 0 else if (v > 255) 255 else v

    /**
     * Pack ARGB to YUV422 (full-range BT.601, 16.16 fixed point; the
     * coefficients sum exactly so greys round-trip unchanged). Odd widths
     * repeat the last column.
     */
    fun pack(px: IntArray, w: Int, h: Int): ByteArray {
        val pw = w + (w and 1)
        val out = ByteArray(pw * h * 2)
        Parallel.rows(h) { from, to -> for (y in from until to) for (x in 0 until pw step 2) {
            val c0 = px[y * w + x]
            val c1 = px[y * w + minOf(x + 1, w - 1)]
            val r0 = (c0 shr 16) and 0xFF
            val g0 = (c0 shr 8) and 0xFF
            val b0 = c0 and 0xFF
            val r1 = (c1 shr 16) and 0xFF
            val g1 = (c1 shr 8) and 0xFF
            val b1 = c1 and 0xFF
            val r2 = r0 + r1
            val g2 = g0 + g1
            val b2 = b0 + b1
            val o = (y * pw + x) * 2
            out[o] = ((19595 * r0 + 38470 * g0 + 7471 * b0 + 32768) shr 16).toByte()
            out[o + 1] = clamp(128 + ((-5529 * r2 - 10855 * g2 + 16384 * b2 + 32768) shr 16)).toByte()
            out[o + 2] = ((19595 * r1 + 38470 * g1 + 7471 * b1 + 32768) shr 16).toByte()
            out[o + 3] = clamp(128 + ((16384 * r2 - 13720 * g2 - 2664 * b2 + 32768) shr 16)).toByte()
        } }
        return out
    }

    fun unpack(bytes: ByteArray, w: Int, h: Int): IntArray {
        val pw = w + (w and 1)
        val out = IntArray(w * h)
        Parallel.rows(h) { from, to -> for (y in from until to) for (x in 0 until pw step 2) {
            val o = (y * pw + x) * 2
            val u = (bytes[o + 1].toInt() and 0xFF) - 128
            val v = (bytes[o + 3].toInt() and 0xFF) - 128
            val dr = (91881 * v + 32768) shr 16
            val dg = (-22554 * u - 46802 * v + 32768) shr 16
            val db = (116130 * u + 32768) shr 16
            for (k in 0..1) {
                if (x + k >= w) continue
                val yy = bytes[o + k * 2].toInt() and 0xFF
                out[y * w + x + k] = (0xFF shl 24) or (clamp(yy + dr) shl 16) or (clamp(yy + dg) shl 8) or clamp(yy + db)
            }
        } }
        return out
    }
}

/**
 * A bent camera. Stateful like the real thing: the frame buffer keeps stale
 * memory between frames and brownouts can hold the previous picture.
 * Everything random is drawn from (seed, frame), so a given seed and frame
 * always produce the same picture.
 */
class BendEngine(private val seed: Long = 1) {
    private var memory: ByteArray? = null
    private var lastClean: IntArray? = null
    private var previous: IntArray? = null
    private var lastW = -1
    private var lastH = -1

    fun process(src: IntArray, width: Int, height: Int, settings: BendSettings, frame: Long): IntArray {
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return IntArray(src.size)
        if (w != lastW || h != lastH) {
            memory = null
            lastClean = null
            previous = null
            lastW = w
            lastH = h
        }
        val s = settings
        if (s.isNeutral) {
            lastClean = src.copyOf()
            memory = null
            previous = src.copyOf()
            return src.copyOf()
        }
        val rnd = Random(seed * 0x5DEECE66DL + frame * 0x2545F491L + 11)
        // Per-row streams let row work run in parallel and stay reproducible.
        val rowSeed = seed * 0x5DEECE66DL + frame * 0x2545F491L + 0x9E3779B9L
        fun rowRng(salt: Int, y: Int) = Random(rowSeed + salt * 0x10001L + y * 0x632BE5ABL)
        var px = src.copyOf()

        // --- Sensor and its ISP -------------------------------------------------
        when (s.sccb) {
            SccbGlitch.COLOR_BARS -> colorBars(px, w, h)
            SccbGlitch.MIRROR -> for (y in 0 until h) px.reverse(y * w, y * w + w)
            else -> {}
        }
        if (s.adcGain != 1f) Parallel.rows(h) { a, b -> for (y in a until b) analogGain(px, y * w, (y + 1) * w, s.adcGain, rowRng(1, y)) }
        if (s.bayerPhase != BayerPhase.NONE) px = bayer(px, w, h, s.bayerPhase)
        when (s.sccb) {
            SccbGlitch.NEGATIVE -> for (i in px.indices) px[i] = px[i] xor 0x00FFFFFF
            SccbGlitch.NEON -> neon(px)
            else -> {}
        }

        // --- Sensor bus, DSP capture, frame memory ---------------------------------
        if (s.busActive || s.timingActive || s.addressActive) {
            val pw = w + (w and 1)
            var bytes = Bus.pack(px, w, h)
            if (s.busActive) {
                val lb = pw * 2
                val bus = bytes
                Parallel.rows(h) { a, b ->
                    for (y in a until b) Bus.applyDataLines(bus, s.dataLines, rowRng(2, y), y * lb, (y + 1) * lb)
                }
                Bus.bridge(bytes, s.bridgeA, s.bridgeB, s.bridge)
                Bus.swap(bytes, s.crossA, s.crossB)
                if (s.crosstalkBit in 0..7) Bus.crosstalk(bytes, pw * 2, s.crosstalkBit, s.crosstalkPeriod.coerceIn(0, 8))
                if (s.bytePhaseSlip) Bus.slipByte(bytes)
            }
            if (s.timingActive) bytes = capture(bytes, pw, h, s, rnd)
            bytes = frameMemory(bytes, w, h, s)
            px = Bus.unpack(bytes, w, h)
        } else {
            memory = null
        }

        // --- Output stage -------------------------------------------------------
        if (s.jpegErrors > 0f) jpeg(px, w, h, s.jpegErrors, rnd)
        when (s.fieldError) {
            FieldError.SWAP -> for (y in 0 until h - 1 step 2) {
                for (x in 0 until w) {
                    val a = px[y * w + x]
                    px[y * w + x] = px[(y + 1) * w + x]
                    px[(y + 1) * w + x] = a
                }
            }
            FieldError.DROP -> for (y in 1 until h step 2) System.arraycopy(px, (y - 1) * w, px, y * w, w)
            FieldError.NONE -> {}
        }
        if (s.vsyncRoll != 0f) px = roll(px, w, h, s.vsyncRoll, frame)
        if (s.brownout > 0f) px = brownout(px, w, h, s.brownout, rnd, ::rowRng)

        lastClean = src.copyOf()
        previous = px.copyOf()
        return px
    }

    /**
     * The DSP clocks samples off the bus: a lost HSYNC makes it wrap lines at
     * the wrong stride; missed pixel clocks make a line start late.
     */
    private fun capture(bytes: ByteArray, pw: Int, h: Int, s: BendSettings, rnd: Random): ByteArray {
        val total = pw * h
        val out = ByteArray(bytes.size)
        val over = ((s.pixelClockPot - BendSettings.PCLK_KNEE) / (1f - BendSettings.PCLK_KNEE)).coerceIn(0f, 1f)
        val slipChance = over.pow(1.5f)
        val maxSlip = 1 + (over * pw * 0.15f).toInt()
        val stride = pw + s.strideOffset
        val slips = IntArray(h) { if (slipChance > 0f && rnd.nextFloat() < slipChance) 1 + rnd.nextInt(maxSlip) else 0 }
        Parallel.rows(h) { a, b ->
            for (y in a until b) {
                var from = Math.floorMod(y.toLong() * stride + slips[y], total.toLong()).toInt()
                for (x in 0 until pw) {
                    val o = (y * pw + x) * 2
                    out[o] = bytes[from * 2]
                    out[o + 1] = bytes[from * 2 + 1]
                    if (++from == total) from = 0
                }
            }
        }
        return out
    }

    /**
     * Write the stream into frame memory through the (possibly stuck)
     * address line, then read the whole buffer back. Addresses the bend makes
     * unreachable keep whatever an earlier frame left there.
     */
    private fun frameMemory(bytes: ByteArray, w: Int, h: Int, s: BendSettings): ByteArray {
        if (!s.addressActive) {
            memory = bytes.copyOf()
            return bytes
        }
        val mem = memory?.takeIf { it.size == bytes.size }
            ?: (lastClean?.let { Bus.pack(it, w, h) } ?: ByteArray(bytes.size))
        val bit = if (s.addressBit in 0..30) 1 shl s.addressBit else 0
        val high = s.addressMode == LineMode.HIGH
        for (addr in bytes.indices) {
            val target = if (high) addr or bit else addr and bit.inv()
            if (target < mem.size) mem[target] = bytes[addr]
        }
        memory = mem
        return mem.copyOf()
    }

    private fun roll(px: IntArray, w: Int, h: Int, speed: Float, frame: Long): IntArray {
        val vbi = maxOf(1, h * 8 / 100)
        val period = h + vbi
        val offset = Math.floorMod(floor(frame * speed.toDouble()).toLong(), period.toLong()).toInt()
        val out = IntArray(px.size)
        val black = 0xFF000000.toInt()
        for (y in 0 until h) {
            val sy = Math.floorMod(y - offset, period)
            if (sy >= h) {
                out.fill(black, y * w, y * w + w)
            } else {
                System.arraycopy(px, sy * w, out, y * w, w)
            }
        }
        return out
    }

    private fun brownout(px: IntArray, w: Int, h: Int, amount: Float, rnd: Random, rowRng: (Int, Int) -> Random): IntArray {
        val prev = previous
        val r = rnd.nextFloat()
        if (prev != null && r < amount * 0.15f) return prev.copyOf()
        var out = px
        if (prev != null && r < amount * 0.4f) {
            out = px.copyOf()
            val split = rnd.nextInt(h)
            System.arraycopy(prev, split * w, out, split * w, (h - split) * w)
        }
        val gain = (65536 * (1f - 0.35f * amount)).toInt()
        val noise = (40f * amount).toInt() + 1
        val sag = out
        Parallel.rows(h) { a, b ->
            for (y in a until b) {
                val rr0 = rowRng(3, y)
                val dead = rr0.nextFloat() < amount * 0.04f
                for (x in 0 until w) {
                    val i = y * w + x
                    if (dead) {
                        sag[i] = 0xFF000000.toInt()
                        continue
                    }
                    val c = sag[i]
                    val n = Math.floorMod(rr0.nextInt(), noise) - noise / 2
                    val rr = (((((c shr 16) and 0xFF) * gain) shr 16) + n).coerceIn(0, 255)
                    val gg = (((((c shr 8) and 0xFF) * gain) shr 16) + n).coerceIn(0, 255)
                    val bb = ((((c and 0xFF) * gain) shr 16) + n).coerceIn(0, 255)
                    sag[i] = (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb
                }
            }
        }
        return out
    }

    private companion object {
        /** SMPTE 75% colour bars, the test pattern a mis-written sensor falls back to. */
        val BARS = intArrayOf(0xBFBFBF, 0xBFBF00, 0x00BFBF, 0x00BF00, 0xBF00BF, 0xBF0000, 0x0000BF)

        fun colorBars(px: IntArray, w: Int, h: Int) {
            for (y in 0 until h) for (x in 0 until w) {
                px[y * w + x] = BARS[x * BARS.size / w] or (0xFF shl 24)
            }
        }

        /** Gain on the analog side of the ADC: crushed or blown, with noise that rises with gain. */
        fun analogGain(px: IntArray, from: Int, to: Int, gain: Float, rnd: Random) {
            val noise = kotlin.math.abs(gain - 1f) * 18f
            for (i in from until to) {
                val c = px[i]
                val n = (rnd.nextFloat() - 0.5f) * noise
                val r = ((((c shr 16) and 0xFF) - 16) * gain + 16 + n).roundToInt().coerceIn(0, 255)
                val g = ((((c shr 8) and 0xFF) - 16) * gain + 16 + n).roundToInt().coerceIn(0, 255)
                val b = (((c and 0xFF) - 16) * gain + 16 + n).roundToInt().coerceIn(0, 255)
                px[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }

        /** boxo.cc's config-bus short: U and V swapped and driven hard - neon with green glitches. */
        fun neon(px: IntArray) {
            for (i in px.indices) {
                val c = px[i]
                val r = ((c shr 16) and 0xFF).toFloat()
                val g = ((c shr 8) and 0xFF).toFloat()
                val b = (c and 0xFF).toFloat()
                val y = 0.299f * r + 0.587f * g + 0.114f * b
                val u = (r - y) * 2.2f
                val v = (b - y) * 2.2f - 30f
                val rr = (y + 1.402f * v).roundToInt().coerceIn(0, 255)
                val gg = (y - 0.344136f * u - 0.714136f * v).roundToInt().coerceIn(0, 255)
                val bb = (y + 1.772f * u).roundToInt().coerceIn(0, 255)
                px[i] = (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb
            }
        }

        /** RGGB colour of the filter at (x, y): 0 = R, 1 = G, 2 = B. */
        private val CFA = intArrayOf(0, 1, 1, 2)

        fun cfa(x: Int, y: Int) = CFA[(x and 1) + (y and 1) * 2]

        /**
         * Re-mosaic through an RGGB filter, then demosaic bilinearly with the
         * ISP's idea of the filter offset by [phase].
         */
        fun bayer(px: IntArray, w: Int, h: Int, phase: BayerPhase): IntArray {
            val raw = IntArray(px.size) { i -> (px[i] shr (16 - 8 * cfa(i % w, i / w))) and 0xFF }
            val out = IntArray(px.size)
            Parallel.rows(maxOf(0, h - 2)) { a, b -> bayerInterior(raw, out, w, a + 1, b + 1, phase) }
            for (y in 0 until h) for (x in 0 until w) {
                if (y in 1 until h - 1 && x in 1 until w - 1) continue
                bayerEdge(raw, out, w, h, x, y, phase)
            }
            return out
        }

        /**
         * Interior pixels in closed form: on an R/B site green is the 4
         * orthogonal neighbours and the opposite colour the 4 diagonals; on a
         * G site the other two sit left/right and above/below.
         */
        private fun bayerInterior(raw: IntArray, out: IntArray, w: Int, from: Int, to: Int, phase: BayerPhase) {
            val rgb = IntArray(3)
            for (y in from until to) for (x in 1 until w - 1) {
                val i = y * w + x
                val own = cfa(x + phase.dx, y + phase.dy)
                rgb[own] = raw[i]
                if (own != 1) {
                    rgb[1] = (raw[i - 1] + raw[i + 1] + raw[i - w] + raw[i + w]) shr 2
                    rgb[2 - own] = (raw[i - w - 1] + raw[i - w + 1] + raw[i + w - 1] + raw[i + w + 1]) shr 2
                } else {
                    rgb[cfa(x + 1 + phase.dx, y + phase.dy)] = (raw[i - 1] + raw[i + 1]) shr 1
                    rgb[cfa(x + phase.dx, y + 1 + phase.dy)] = (raw[i - w] + raw[i + w]) shr 1
                }
                out[i] = (0xFF shl 24) or (rgb[0] shl 16) or (rgb[1] shl 8) or rgb[2]
            }
        }

        /** Border pixels: average whatever neighbours exist, own site weighted 4. */
        private fun bayerEdge(raw: IntArray, out: IntArray, w: Int, h: Int, x: Int, y: Int, phase: BayerPhase) {
            val sum = IntArray(3)
            val cnt = IntArray(3)
            for (dy in -1..1) for (dx in -1..1) {
                val nx = x + dx
                val ny = y + dy
                if (nx !in 0 until w || ny !in 0 until h) continue
                val c = cfa(nx + phase.dx, ny + phase.dy)
                val wgt = if (dx == 0 && dy == 0) 4 else 1
                sum[c] += raw[ny * w + nx] * wgt
                cnt[c] += wgt
            }
            val own = cfa(x + phase.dx, y + phase.dy)
            val r = if (own == 0) raw[y * w + x] else if (cnt[0] > 0) sum[0] / cnt[0] else 0
            val g = if (own == 1) raw[y * w + x] else if (cnt[1] > 0) sum[1] / cnt[1] else 0
            val b = if (own == 2) raw[y * w + x] else if (cnt[2] > 0) sum[2] / cnt[2] else 0
            out[y * w + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }

        /**
         * Bit errors in a JPEG's entropy-coded data: from the corrupted MCU
         * onward, blocks are decoded out of step and their DC terms drift,
         * giving shifted 16x16 blocks and per-block colour casts.
         */
        fun jpeg(px: IntArray, w: Int, h: Int, amount: Float, rnd: Random) {
            val bs = 16
            val bw = (w + bs - 1) / bs
            val bh = (h + bs - 1) / bs
            val blocks = bw * bh
            if (blocks < 2) {
                // Too small for a desync to show: the error just casts the colour.
                val dc = ((rnd.nextFloat() - 0.5f) * 120 * amount).roundToInt()
                for (i in px.indices) px[i] = shiftDc(px[i], dc, -dc, dc / 2)
                return
            }
            val events = maxOf(1, (amount * 4).roundToInt())
            val src = px.copyOf()
            val order = (0 until events).map { rnd.nextInt(blocks) }.sorted()
            var shift = 0
            var dr = 0
            var dg = 0
            var db = 0
            var next = 0
            for (b in 0 until blocks) {
                while (next < order.size && order[next] <= b) {
                    shift += 1 + rnd.nextInt(3)
                    dr += ((rnd.nextFloat() - 0.5f) * 90 * amount).roundToInt()
                    dg += ((rnd.nextFloat() - 0.5f) * 90 * amount).roundToInt()
                    db += ((rnd.nextFloat() - 0.5f) * 90 * amount).roundToInt()
                    next++
                }
                if (next == 0) continue
                val from = Math.floorMod(b - shift, blocks)
                val smear = rnd.nextFloat() < 0.3f * amount
                val bx = (b % bw) * bs
                val by = (b / bw) * bs
                val fx = (from % bw) * bs
                val fy = (from / bw) * bs
                val flat = if (smear) src[minOf(h - 1, fy + bs / 2) * w + minOf(w - 1, fx + bs / 2)] else 0
                for (y in 0 until bs) for (x in 0 until bs) {
                    val tx = bx + x
                    val ty = by + y
                    if (tx >= w || ty >= h) continue
                    val c = if (smear) flat else src[minOf(h - 1, fy + y) * w + minOf(w - 1, fx + x)]
                    px[ty * w + tx] = shiftDc(c, dr, dg, db)
                }
            }
        }

        fun shiftDc(c: Int, dr: Int, dg: Int, db: Int): Int {
            val r = (((c shr 16) and 0xFF) + dr).coerceIn(0, 255)
            val g = (((c shr 8) and 0xFF) + dg).coerceIn(0, 255)
            val b = ((c and 0xFF) + db).coerceIn(0, 255)
            return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }
}
