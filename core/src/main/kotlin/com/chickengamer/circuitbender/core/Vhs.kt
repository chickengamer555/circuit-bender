package com.chickengamer.circuitbender.core

import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * Consumer camcorder + VHS tape. Defaults follow docs/RESEARCH.md section 2;
 * distances are in working-buffer pixels, calibrated for 320 px per line.
 */
data class VhsSettings(
    /** Blend toward a 3-tap low-pass: ~3 MHz luma at 320 px is only a gentle blur. */
    val lumaSmear: Float = 0.5f,
    /** Edge-enhancement (peaking) gain; gives the 10-30% overshoot halo. */
    val sharpen: Float = 0.5f,
    /** Luma pixels per chroma sample: 320 / 40 = 8 for VHS colour-under. */
    val chromaDivisor: Int = 8,
    /** Chroma trails luma by ~300 ns, about 2 px at 320 wide. */
    val chromaDelayPx: Float = 2f,
    val saturation: Float = 1.05f,
    val lumaNoise: Float = 0.005f,
    val chromaNoise: Float = 0.04f,
    /** RF dropout specks per pixel (ntsc-rs default). */
    val snow: Float = 0.0001f,
    /** Expected dropout streaks per frame / 2. */
    val dropouts: Float = 0.1f,
    /** Time-base error: peak per-line horizontal wobble. */
    val jitterPx: Float = 0.4f,
    /** Height of the head-switching band in rows (absolute, at this frame size). */
    val headSwitchLines: Int = 4,
    /** 0..1 mistracking: a noisy, displaced band that crawls up the picture. */
    val tracking: Float = 0f,
    /** Interline CCD vertical smear from clipped highlights. */
    val ccdSmear: Float = 0.01f,
) {
    /** The deterministic part only: bandwidth, delay, sharpening and colour. */
    fun noiseless(): VhsSettings = copy(
        lumaNoise = 0f, chromaNoise = 0f, snow = 0f, dropouts = 0f, jitterPx = 0f,
        headSwitchLines = 0, tracking = 0f, ccdSmear = 0f,
    )

    companion object {
        /** Standard play, long play and extended play: slower tape, less bandwidth, more noise. */
        fun forSpeed(speed: String): VhsSettings = when (speed) {
            "LP" -> VhsSettings(lumaSmear = 0.75f, chromaNoise = 0.18f, lumaNoise = 0.016f, jitterPx = 1.5f, saturation = 1.0f, dropouts = 0.6f)
            "EP" -> VhsSettings(
                lumaSmear = 1f, chromaDivisor = 10, chromaNoise = 0.28f, lumaNoise = 0.024f,
                jitterPx = 2f, saturation = 0.85f, dropouts = 1f, snow = 0.0008f,
            )
            else -> VhsSettings()
        }
    }
}

object Vhs {
    private const val KNEE = 235f

    /** Independent stream per effect, so turning one artifact on never reshuffles another. */
    private fun rng(seed: Long, frame: Long, effect: Int) =
        Random(seed * 0x9E3779B97F4A7C15uL.toLong() + frame * 1_000_003L + effect * 7919L)

    fun process(src: IntArray, width: Int, height: Int, settings: VhsSettings, seed: Long, frame: Long): IntArray {
        val w = width
        val h = height
        val n = w * h
        val out = IntArray(src.size)
        if (w <= 0 || h <= 0) return out
        val s = settings

        val yp = FloatArray(n)
        val up = FloatArray(n)
        val vp = FloatArray(n)
        val su = 0.492f * s.saturation
        val sv = 0.877f * s.saturation
        Parallel.rows(h) { from, to ->
            for (i in from * w until to * w) {
                val c = src[i]
                val r = ((c shr 16) and 0xFF).toFloat()
                val g = ((c shr 8) and 0xFF).toFloat()
                val b = (c and 0xFF).toFloat()
                val y = 0.299f * r + 0.587f * g + 0.114f * b
                yp[i] = y
                up[i] = (b - y) * su
                vp[i] = (r - y) * sv
            }
        }

        Parallel.rows(h) { from, to -> luma(yp, w, from, to, s) }

        // Smear is charge in the CCD's vertical register, not image detail:
        // it goes on after the peaking so it is not sharpened into stripes.
        if (s.ccdSmear > 0f) ccdSmear(yp, src, w, h, s.ccdSmear)

        // Chroma: ~40 samples per line, vertically averaged over two lines,
        // delayed to the right.
        chroma(up, w, h, s.chromaDivisor, s.chromaDelayPx)
        chroma(vp, w, h, s.chromaDivisor, s.chromaDelayPx)

        if (s.lumaNoise > 0f || s.chromaNoise > 0f) noise(yp, up, vp, w, h, s, seed, frame)
        if (s.snow > 0f) snow(yp, up, vp, w, h, s.snow, rng(seed, frame, 2))
        if (s.dropouts > 0f) dropouts(yp, up, vp, w, h, s.dropouts, rng(seed, frame, 3))
        if (s.tracking > 0f) tracking(yp, up, vp, w, h, s.tracking, frame, rng(seed, frame, 4))
        if (s.jitterPx > 0f) jitter(yp, up, vp, w, h, s.jitterPx, frame, rng(seed, frame, 5))
        if (s.headSwitchLines > 0) headSwitch(yp, up, vp, w, h, s.headSwitchLines, rng(seed, frame, 6))

        Parallel.rows(h) { from, to ->
            for (i in from * w until to * w) {
                val y = yp[i]
                val u = up[i]
                val v = vp[i]
                val r = byte(y + 1.140f * v)
                val g = byte(y - 0.395f * u - 0.581f * v)
                val b = byte(y + 2.032f * u)
                out[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return out
    }

    /** Round and clamp to 0..255 without roundToInt's NaN handling. */
    private fun byte(v: Float): Int {
        val i = (v + 0.5f).toInt()
        return if (i < 0) 0 else if (i > 255) 255 else i
    }

    private val BLUR5 = floatArrayOf(1 / 16f, 4 / 16f, 6 / 16f, 4 / 16f, 1 / 16f)

    /** Luma: gentle low-pass, then peaking against a wider blur, then the highlight knee. */
    private fun luma(yp: FloatArray, w: Int, from: Int, to: Int, s: VhsSettings) {
        val line = FloatArray(w)
        val tmp = FloatArray(w)
        val keep = 1 - s.lumaSmear
        val side = s.lumaSmear * 0.25f
        val mid = s.lumaSmear * 0.5f
        val last = w - 1
        for (y in from until to) {
            val o = y * w
            System.arraycopy(yp, o, line, 0, w)
            for (x in 0 until w) {
                val a = line[if (x > 0) x - 1 else 0]
                val c = line[if (x < last) x + 1 else last]
                tmp[x] = line[x] * (keep + mid) + side * (a + c)
            }
            for (x in 0 until w) {
                val blur = if (x >= 2 && x <= last - 2) {
                    (tmp[x - 2] + tmp[x + 2] + 4 * (tmp[x - 1] + tmp[x + 1]) + 6 * tmp[x]) * (1 / 16f)
                } else {
                    var b = 0f
                    for (k in -2..2) b += tmp[(x + k).coerceIn(0, last)] * BLUR5[k + 2]
                    b
                }
                val v = tmp[x] + s.sharpen * (tmp[x] - blur)
                yp[o + x] = if (v > KNEE) KNEE + (v - KNEE) * 0.4f else v
            }
        }
    }

    /**
     * Charge from clipped highlights leaks into the vertical register:
     * whole-column streaks. Only light near clipping counts (measured on the
     * sensor image, before any processing), and a column can only brighten
     * so far.
     */
    private fun ccdSmear(yp: FloatArray, src: IntArray, w: Int, h: Int, k: Float) {
        for (x in 0 until w) {
            var excess = 0f
            for (y in 0 until h) excess += maxOf(0f, luma(src[y * w + x]) - CLIP)
            if (excess <= 0f) continue
            val add = minOf(SMEAR_CAP, k * excess * 0.5f)
            for (y in 0 until h) yp[y * w + x] += add
        }
    }

    private const val CLIP = 235f
    private const val SMEAR_CAP = 60f

    private fun chroma(c: FloatArray, w: Int, h: Int, divisor: Int, delay: Float) {
        val d = maxOf(1, divisor)
        val samples = (w + d - 1) / d
        val smooth = FloatArray(samples * h)
        // Pass 1: decimate each line to ~40 samples and low-pass them.
        Parallel.rows(h) { from, to ->
            val dec = FloatArray(samples)
            for (y in from until to) {
                val o = y * w
                for (i in 0 until samples) {
                    var sum = 0f
                    var count = 0
                    for (x in i * d until minOf(w, i * d + d)) {
                        sum += c[o + x]
                        count++
                    }
                    dec[i] = sum / count
                }
                val so = y * samples
                for (i in 0 until samples) {
                    smooth[so + i] = 0.25f * dec[maxOf(0, i - 1)] + 0.5f * dec[i] + 0.25f * dec[minOf(samples - 1, i + 1)]
                }
            }
        }
        // Pass 2: comb / delay-line averaging with the line above halves the
        // vertical chroma resolution; then upsample with the Y/C delay.
        val centre = (d - 1) / 2f
        val maxP = (samples - 1).toFloat()
        val inv = 1f / d
        Parallel.rows(h) { from, to ->
            val row = FloatArray(samples)
            for (y in from until to) {
                val so = y * samples
                val po = if (y > 0) so - samples else so
                for (i in 0 until samples) row[i] = 0.5f * (smooth[so + i] + smooth[po + i])
                val o = y * w
                for (x in 0 until w) {
                    var p = (x - delay - centre) * inv
                    if (p < 0f) p = 0f else if (p > maxP) p = maxP
                    val i0 = p.toInt()
                    val i1 = if (i0 + 1 < samples) i0 + 1 else i0
                    val f = p - i0
                    c[o + x] = row[i0] * (1 - f) + row[i1] * f
                }
            }
        }
    }

    private fun noise(yp: FloatArray, up: FloatArray, vp: FloatArray, w: Int, h: Int, s: VhsSettings, seed: Long, frame: Long) {
        val ly = s.lumaNoise * 255f * 2.5f
        val lc = s.chromaNoise * 60f
        Parallel.rows(h) { from, to ->
            for (y in from until to) {
                // Each line draws from its own stream so lines can be noised in parallel.
                val rnd = rng(seed, frame, 1000 + y)
                val o = y * w
                // Horizontally stretched luma grain.
                var grain = 0f
                // Chroma blotches: slow random walk along the line.
                var cu = (rnd.nextFloat() - 0.5f) * lc
                var cv = (rnd.nextFloat() - 0.5f) * lc
                for (x in 0 until w) {
                    // One random word feeds all three: 10 bits each.
                    val bits = rnd.nextInt()
                    grain = grain * 0.55f + ((bits and 0x3FF) * (1 / 1024f) - 0.5f)
                    yp[o + x] += grain * ly
                    if (x and 3 == 0) {
                        cu = cu * 0.88f + (((bits ushr 10) and 0x3FF) * (1 / 1024f) - 0.5f) * lc * 0.5f
                        cv = cv * 0.88f + (((bits ushr 20) and 0x3FF) * (1 / 1024f) - 0.5f) * lc * 0.5f
                    }
                    up[o + x] += cu
                    vp[o + x] += cv
                }
            }
        }
    }

    /** Short white horizontal specks. */
    private fun snow(yp: FloatArray, up: FloatArray, vp: FloatArray, w: Int, h: Int, rate: Float, rnd: Random) {
        val count = (w * h * rate).let { it.toInt() + if (rnd.nextFloat() < it - it.toInt()) 1 else 0 }
        repeat(count) {
            val y = rnd.nextInt(h)
            val x0 = rnd.nextInt(w)
            val len = 1 + rnd.nextInt(4)
            for (x in x0 until minOf(w, x0 + len)) {
                val i = y * w + x
                yp[i] = 230f + rnd.nextFloat() * 25f
                up[i] *= 0.2f
                vp[i] *= 0.2f
            }
        }
    }

    /** Oxide dropouts: a white streak, or - with a dropout compensator - the previous line repeated. */
    private fun dropouts(yp: FloatArray, up: FloatArray, vp: FloatArray, w: Int, h: Int, rate: Float, rnd: Random) {
        var count = 0
        var p = rate * 2f
        while (p > 0f) {
            if (rnd.nextFloat() < minOf(1f, p)) count++
            p -= 1f
        }
        repeat(count) {
            val y = rnd.nextInt(h)
            val len = minOf(w, 5 + rnd.nextInt(46) * w / 320)
            val x0 = rnd.nextInt(maxOf(1, w - len + 1))
            val doc = rnd.nextBoolean() && y > 0
            for (x in x0 until minOf(w, x0 + len)) {
                val i = y * w + x
                if (doc) {
                    yp[i] = yp[i - w]
                    up[i] = up[i - w]
                    vp[i] = vp[i - w]
                } else {
                    yp[i] = 255f
                    up[i] = 0f
                    vp[i] = 0f
                }
            }
        }
    }

    private fun shiftLine(c: FloatArray, o: Int, w: Int, by: Float, buf: FloatArray, fill: Float?) {
        val whole = floor(by).toInt()
        val f0 = by - whole
        if (fill == null && f0 == 0f && whole == 0) return
        if (fill == null && kotlin.math.abs(whole) < w - 1) {
            // Fast path: constant split, clamped at the ends. The sample at
            // x - whole - 1 carries weight f0.
            val f = 1f - f0
            for (x in 0 until w) {
                val i1 = (x - whole).coerceIn(0, w - 1)
                val i0 = (x - whole - 1).coerceIn(0, w - 1)
                buf[x] = c[o + i1] * f + c[o + i0] * f0
            }
            buf.copyInto(c, o, 0, w)
            return
        }
        for (x in 0 until w) {
            val sx = x - by
            val i0 = floor(sx).toInt()
            val f = sx - i0
            buf[x] = if (fill != null && (i0 < 0 || i0 + 1 > w - 1)) {
                fill
            } else {
                val a = c[o + i0.coerceIn(0, w - 1)]
                val b = c[o + (i0 + 1).coerceIn(0, w - 1)]
                a * (1 - f) + b * f
            }
        }
        buf.copyInto(c, o, 0, w)
    }

    /** Time-base error: smooth per-line wobble, no TBC. */
    private fun jitter(yp: FloatArray, up: FloatArray, vp: FloatArray, w: Int, h: Int, amount: Float, frame: Long, rnd: Random) {
        val phase = rnd.nextFloat() * 2 * PI.toFloat()
        val phase2 = rnd.nextFloat() * 2 * PI.toFloat()
        var walk = 0f
        val offsets = FloatArray(h)
        for (y in 0 until h) {
            walk = walk * 0.8f + (rnd.nextFloat() - 0.5f) * 0.6f
            val t = y.toFloat() / maxOf(1, h)
            offsets[y] = amount * (0.6f * sin(t * 2 * PI.toFloat() * 3 + phase + frame * 0.4f) + 0.25f * sin(t * 37f + phase2) + walk)
        }
        Parallel.rows(h) { from, to ->
            val buf = FloatArray(w)
            for (y in from until to) {
                shiftLine(yp, y * w, w, offsets[y], buf, null)
                shiftLine(up, y * w, w, offsets[y], buf, null)
                shiftLine(vp, y * w, w, offsets[y], buf, null)
            }
        }
    }

    /** Mistracking: a band of displaced, noisy lines that crawls up the frame. */
    private fun tracking(yp: FloatArray, up: FloatArray, vp: FloatArray, w: Int, h: Int, amount: Float, frame: Long, rnd: Random) {
        val band = maxOf(1, (12 * h / 240f * (0.5f + amount)).roundToInt())
        val y0 = Math.floorMod(h - (frame * 3).toInt(), h + band) - band
        val buf = FloatArray(w)
        for (k in 0 until band) {
            val y = y0 + k
            if (y !in 0 until h) continue
            val off = amount * w * 0.08f * (0.5f + rnd.nextFloat())
            shiftLine(yp, y * w, w, off, buf, 20f)
            shiftLine(up, y * w, w, off, buf, 0f)
            shiftLine(vp, y * w, w, off, buf, 0f)
            for (x in 0 until w) {
                if (rnd.nextFloat() < 0.025f + amount * 0.1f) {
                    yp[y * w + x] = 200f + rnd.nextFloat() * 55f
                } else {
                    yp[y * w + x] += (rnd.nextFloat() - 0.5f) * 60f * amount
                }
            }
        }
    }

    /**
     * Head-switching noise: the last lines before vertical sync come from the
     * other head, so they are skewed sideways with ragged noise on the left.
     */
    private fun headSwitch(yp: FloatArray, up: FloatArray, vp: FloatArray, w: Int, h: Int, lines: Int, rnd: Random) {
        val band = minOf(lines, h)
        val buf = FloatArray(w)
        val bounce = 1f + rnd.nextFloat() * 0.6f
        for (k in 0 until band) {
            val y = h - band + k
            val off = maxOf(1f, w * 0.04f * (k + 1) * bounce)
            shiftLine(yp, y * w, w, off, buf, 16f)
            shiftLine(up, y * w, w, off, buf, 0f)
            shiftLine(vp, y * w, w, off, buf, 0f)
            val ragged = minOf(w, off.toInt() + 2)
            for (x in 0 until ragged) yp[y * w + x] = rnd.nextFloat() * 120f
        }
    }
}
