package com.chickengamer.circuitbender.core

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BendTest {
    private fun grey(v: Int) = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    private fun red(c: Int) = (c shr 16) and 0xFF
    private fun green(c: Int) = (c shr 8) and 0xFF
    private fun blue(c: Int) = c and 0xFF

    /** Greyscale survives the YUV422 round trip exactly, so pixel positions can be traced. */
    private fun greyRamp(w: Int, h: Int, offset: Int = 0) =
        IntArray(w * h) { grey(40 + ((it * 7 + offset) % 180)) }

    /** A colourful chart with structure in both directions. */
    private fun chart(w: Int, h: Int) = IntArray(w * h) {
        val x = it % w
        val y = it / w
        rgb((x * 255) / maxOf(1, w - 1), (y * 255) / maxOf(1, h - 1), ((x + y) * 9) and 0xFF)
    }

    private fun lines(vararg set: Pair<Int, DataLine>): List<DataLine> {
        val l = MutableList(8) { DataLine() }
        for ((i, d) in set) l[i] = d
        return l
    }

    @Test
    fun `neutral rack is a perfect bypass`() {
        val w = 33
        val h = 17
        val src = chart(w, h)
        val copy = src.copyOf()
        val engine = BendEngine(seed = 7)
        assertTrue(BendSettings().isNeutral)
        for (frame in 0L until 3L) {
            val out = engine.process(src, w, h, BendSettings(), frame)
            assertContentEquals(copy, out)
        }
        assertContentEquals(copy, src, "input must not be modified")
    }

    @Test
    fun `stuck data line forces the bit through the pot`() {
        val rnd = Random(3)
        val bytes = ByteArray(20_000) { rnd.nextInt(256).toByte() }

        val shorted = bytes.copyOf()
        Bus.applyDataLines(shorted, lines(7 to DataLine(LineMode.HIGH, pot = 0f)), Random(1))
        assertTrue(shorted.all { it.toInt() and 0x80 != 0 }, "hard short must set D7 on every byte")
        // Other bits are untouched.
        for (i in bytes.indices) assertEquals(bytes[i].toInt() and 0x7F, shorted[i].toInt() and 0x7F)

        val open = bytes.copyOf()
        Bus.applyDataLines(open, lines(7 to DataLine(LineMode.HIGH, pot = 1f)), Random(1))
        assertContentEquals(bytes, open, "an open pot leaves the bus alone")

        val low = bytes.copyOf()
        Bus.applyDataLines(low, lines(0 to DataLine(LineMode.LOW, pot = 0f)), Random(1))
        assertTrue(low.all { it.toInt() and 1 == 0 }, "D0 tied low clears bit 0 everywhere")

        val half = bytes.copyOf()
        Bus.applyDataLines(half, lines(7 to DataLine(LineMode.HIGH, pot = 0.5f)), Random(1))
        val eligible = bytes.indices.filter { bytes[it].toInt() and 0x80 == 0 }
        val flipped = eligible.count { half[it].toInt() and 0x80 != 0 }.toDouble() / eligible.size
        assertEquals(0.5, flipped, 0.05, "pot halfway flips about half the eligible bytes")
    }

    @Test
    fun `bridged and crossed lines rewire bits`() {
        // 0b1000_0001: D7=1, D6=0, D0=1
        val and = byteArrayOf(0x81.toByte(), 0xC0.toByte(), 0x40)
        Bus.bridge(and, 7, 6, BridgeMode.AND)
        // AND: both lines take b7 & b6.
        assertContentEquals(byteArrayOf(0x01, 0xC0.toByte(), 0x00), and)

        val or = byteArrayOf(0x81.toByte(), 0xC0.toByte(), 0x40)
        Bus.bridge(or, 7, 6, BridgeMode.OR)
        assertContentEquals(byteArrayOf(0xC1.toByte(), 0xC0.toByte(), 0xC0.toByte()), or)

        val swapped = byteArrayOf(0x81.toByte(), 0x01, 0x80.toByte(), 0x7E)
        Bus.swap(swapped, 0, 7)
        assertContentEquals(byteArrayOf(0x81.toByte(), 0x80.toByte(), 0x01, 0x7E), swapped)
    }

    @Test
    fun `hsync loss rewraps lines at the wrong stride`() {
        val w = 24
        val h = 10
        val src = greyRamp(w, h)
        for (d in intArrayOf(3, -2)) {
            val out = BendEngine(1).process(src, w, h, BendSettings(strideOffset = d), 0)
            for (y in 0 until h) for (x in 0 until w) {
                val from = Math.floorMod(y * (w + d) + x, w * h)
                assertEquals(src[from], out[y * w + x], "stride $d at $x,$y")
            }
        }
    }

    @Test
    fun `pixel clock pot has a knee`() {
        val w = 40
        val h = 60
        val src = greyRamp(w, h)
        fun shiftedRows(pot: Float): Int {
            val out = BendEngine(5).process(src, w, h, BendSettings(pixelClockPot = pot), 0)
            return (0 until h).count { y ->
                (0 until w).any { x -> out[y * w + x] != src[y * w + x] }
            }
        }
        assertEquals(0, shiftedRows(0.3f), "below the knee the clock still makes every edge")
        assertTrue(shiftedRows(1f) > h / 4, "fully turned, many lines tear")
    }

    @Test
    fun `vsync loss rolls with a blanking bar`() {
        val w = 16
        val h = 60
        // Distinct non-black rows.
        val src = IntArray(w * h) { grey(60 + (it / w) * 3) }
        val engine = BendEngine(2)
        val s = BendSettings(vsyncRoll = 5f)
        val f1 = engine.process(src, w, h, s, 1)
        val f2 = engine.process(src, w, h, s, 2)
        val black = grey(0)
        val blankRows = (0 until h).count { y -> (0 until w).all { f1[y * w + it] == black } }
        assertTrue(blankRows >= 1, "a blanking bar is visible")
        // The picture moves down 5 rows per frame.
        var matched = 0
        for (y in 0 until h - 5) {
            val row1 = f1.copyOfRange(y * w, y * w + w)
            val row2 = f2.copyOfRange((y + 5) * w, (y + 5) * w + w)
            if (row1.contentEquals(row2) && row1[0] != black) matched++
        }
        assertTrue(matched > h / 2, "rows of frame 1 reappear 5 lines lower in frame 2 ($matched)")
    }

    @Test
    fun `stuck address line repeats blocks and leaves stale frames`() {
        val w = 16
        val h = 16 // stream = 16*16*2 = 512 bytes; bit 6 = 64 bytes = 32 samples = 2 rows
        val a = greyRamp(w, h, 0)
        val b = greyRamp(w, h, 101)
        fun rows(px: IntArray, from: Int, to: Int) = px.copyOfRange(from * w, to * w)

        val low = BendEngine(1)
        low.process(a, w, h, BendSettings(), 0)
        val outLow = low.process(b, w, h, BendSettings(addressBit = 6, addressMode = LineMode.LOW), 1)
        assertContentEquals(rows(b, 2, 4), rows(outLow, 0, 2), "stuck low: block shows the following block")
        assertContentEquals(rows(a, 2, 4), rows(outLow, 2, 4), "stuck low: the unreachable block keeps the old frame")

        val high = BendEngine(1)
        high.process(a, w, h, BendSettings(), 0)
        val outHigh = high.process(b, w, h, BendSettings(addressBit = 6, addressMode = LineMode.HIGH), 1)
        assertContentEquals(rows(a, 0, 2), rows(outHigh, 0, 2), "stuck high: lower block is stale")
        assertContentEquals(rows(b, 2, 4), rows(outHigh, 2, 4), "stuck high: upper block is live")
    }

    @Test
    fun `bayer phase error swaps colour channels`() {
        val w = 8
        val h = 8
        val src = IntArray(w * h) { rgb(255, 0, 0) }
        fun means(phase: BayerPhase): Triple<Double, Double, Double> {
            val out = BendEngine(1).process(src, w, h, BendSettings(bayerPhase = phase), 0)
            return Triple(out.map(::red).average(), out.map(::green).average(), out.map(::blue).average())
        }
        val (r1, g1, b1) = means(BayerPhase.SHIFT_X)
        assertTrue(g1 > r1 + 40 && g1 > b1 + 40, "phase (1,0) turns red green: r=$r1 g=$g1 b=$b1")
        val (r2, g2, b2) = means(BayerPhase.SHIFT_XY)
        assertTrue(b2 > r2 + 80 && b2 > g2 + 80, "phase (1,1) turns red blue: r=$r2 g=$g2 b=$b2")
    }

    @Test
    fun `every control changes the picture`() {
        val w = 64
        val h = 48
        val src = chart(w, h)
        val controls = mapOf(
            "data line" to BendSettings(dataLines = lines(5 to DataLine(LineMode.HIGH, 0f))),
            "bridge" to BendSettings(bridge = BridgeMode.OR, bridgeA = 3, bridgeB = 6),
            "cross" to BendSettings(crossA = 1, crossB = 6),
            "crosstalk" to BendSettings(crosstalkBit = 6),
            "byte phase" to BendSettings(bytePhaseSlip = true),
            "pixel clock" to BendSettings(pixelClockPot = 1f),
            "stride" to BendSettings(strideOffset = 5),
            "vsync" to BendSettings(vsyncRoll = 3f),
            "address" to BendSettings(addressMode = LineMode.HIGH, addressBit = 9),
            "bayer" to BendSettings(bayerPhase = BayerPhase.SHIFT_Y),
            "adc gain" to BendSettings(adcGain = 2.5f),
            "sccb" to BendSettings(sccb = SccbGlitch.NEON),
            "jpeg" to BendSettings(jpegErrors = 1f),
            "field" to BendSettings(fieldError = FieldError.SWAP),
            "brownout" to BendSettings(brownout = 1f),
        )
        for ((name, s) in controls) {
            assertFalse(s.isNeutral, "$name must not count as neutral")
            val a = BendEngine(9).process(src, w, h, s, 4)
            assertFalse(a.contentEquals(src), "$name changed nothing")
            // A bend corrupts the picture; it must not replace it with nothing.
            assertTrue(a.all { it ushr 24 == 0xFF }, "$name output must stay opaque")
            assertTrue(a.toSet().size >= 16, "$name destroyed the picture entirely")
            val again = BendEngine(9).process(src, w, h, s, 4)
            assertContentEquals(a, again, "$name is not reproducible")
        }
    }

    @Test
    fun `odd and tiny frames are safe`() {
        val everything = BendSettings(
            dataLines = lines(7 to DataLine(LineMode.HIGH, 0.3f), 2 to DataLine(LineMode.LOW, 0f)),
            bridge = BridgeMode.AND, crossA = 0, crossB = 5, crosstalkBit = 4, bytePhaseSlip = true,
            pixelClockPot = 1f, strideOffset = -7, vsyncRoll = 9f, addressMode = LineMode.LOW, addressBit = 3,
            bayerPhase = BayerPhase.SHIFT_XY, adcGain = 3f, sccb = SccbGlitch.COLOR_BARS, jpegErrors = 1f,
            fieldError = FieldError.DROP, brownout = 1f,
        )
        for ((w, h) in listOf(1 to 1, 3 to 1, 1 to 5, 7 to 3)) {
            val engine = BendEngine(4)
            for (f in 0L until 4L) {
                val out = engine.process(chart(w, h), w, h, everything, f)
                assertEquals(w * h, out.size, "${w}x$h")
                assertTrue(out.all { it ushr 24 == 0xFF }, "${w}x$h output must stay opaque")
            }
        }
        assertEquals(0, BendEngine(1).process(IntArray(0), 0, 0, everything, 0).size)
    }
}
