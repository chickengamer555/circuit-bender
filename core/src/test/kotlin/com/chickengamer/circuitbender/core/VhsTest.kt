package com.chickengamer.circuitbender.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VhsTest {
    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    private fun grey(v: Int) = rgb(v, v, v)
    private fun y(c: Int) = luma(c).toDouble()

    /** Rec.601 V (R - Y) in 0..255 space. */
    private fun v(c: Int) = (((c shr 16) and 0xFF) - luma(c)).toDouble()

    private val clean = VhsSettings().noiseless()

    /** Left half [left], right half [right], edge at x = w/2. */
    private fun split(w: Int, h: Int, left: Int, right: Int) = IntArray(w * h) { if (it % w < w / 2) left else right }

    /** Width between the 10% and 90% crossings of a monotone-ish transition. */
    private fun riseWidth(row: DoubleArray): Double {
        val lo = row.first()
        val hi = row.last()
        fun cross(f: Double): Double {
            val target = lo + (hi - lo) * f
            for (i in 1 until row.size) {
                val a = row[i - 1] - target
                val b = row[i] - target
                if (a == 0.0) return (i - 1).toDouble()
                if (a * b < 0 || b == 0.0) return i - 1 + a / (a - b)
            }
            return Double.NaN
        }
        return kotlin.math.abs(cross(0.9) - cross(0.1))
    }

    private fun midpoint(row: DoubleArray): Double {
        val target = (row.first() + row.last()) / 2
        for (i in 1 until row.size) {
            val a = row[i - 1] - target
            val b = row[i] - target
            if (a * b <= 0 && a != b) return i - 1 + a / (a - b)
        }
        return Double.NaN
    }

    private fun row(px: IntArray, w: Int, r: Int, f: (Int) -> Double) = DoubleArray(w) { f(px[r * w + it]) }

    @Test
    fun `chroma is eight times softer than luma`() {
        val w = 80
        val h = 6
        val colour = Vhs.process(split(w, h, rgb(200, 40, 40), rgb(40, 40, 200)), w, h, clean, seed = 1, frame = 0)
        val chromaWidth = riseWidth(row(colour, w, 3, ::v))
        assertTrue(chromaWidth >= 6.0, "colour edge should spread >= 6 px, was $chromaWidth")

        val mono = Vhs.process(split(w, h, grey(30), grey(220)), w, h, clean, seed = 1, frame = 0)
        val lumaWidth = riseWidth(row(mono, w, 3, ::y))
        assertTrue(lumaWidth <= 3.0, "grey edge 10-90% rise should be <= 3 px, was $lumaWidth")
        assertTrue(chromaWidth >= 2.5 * lumaWidth, "chroma ($chromaWidth) much softer than luma ($lumaWidth)")
    }

    @Test
    fun `chroma is delayed to the right`() {
        val w = 80
        val h = 6
        val out = Vhs.process(split(w, h, rgb(200, 40, 40), rgb(40, 40, 200)), w, h, clean, seed = 1, frame = 0)
        val lumaMid = midpoint(row(out, w, 3, ::y))
        val chromaMid = midpoint(row(out, w, 3, ::v))
        val delay = chromaMid - lumaMid
        assertTrue(delay in 1.0..3.0, "chroma should trail luma by 1-3 px, was $delay (luma $lumaMid, chroma $chromaMid)")
    }

    @Test
    fun `sharpening overshoots edges`() {
        val w = 80
        val h = 4
        val out = Vhs.process(split(w, h, grey(60), grey(180)), w, h, clean, seed = 1, frame = 0)
        val r = row(out, w, 2, ::y)
        val plateauHi = r[w - 5]
        val plateauLo = r[4]
        assertTrue(r.max() > plateauHi + 3, "overshoot above ${plateauHi}: max ${r.max()}")
        assertTrue(r.min() < plateauLo - 3, "undershoot below ${plateauLo}: min ${r.min()}")
    }

    @Test
    fun `head switching band sits at the bottom`() {
        val w = 64
        val h = 60
        val src = IntArray(w * h) { rgb((it % w) * 4, 128, 255 - (it % w) * 4) }
        val withBand = clean.copy(headSwitchLines = 7)
        val without = clean.copy(headSwitchLines = 0)
        val a = Vhs.process(src, w, h, withBand, seed = 3, frame = 5)
        val b = Vhs.process(src, w, h, without, seed = 3, frame = 5)
        for (r in 0 until h - 7) {
            assertContentEquals(b.copyOfRange(r * w, r * w + w), a.copyOfRange(r * w, r * w + w), "row $r above the band")
        }
        for (r in h - 7 until h) {
            assertFalse(b.copyOfRange(r * w, r * w + w).contentEquals(a.copyOfRange(r * w, r * w + w)), "row $r in the band")
        }
    }

    @Test
    fun `ccd smears bright points vertically`() {
        val w = 40
        val h = 30
        val src = IntArray(w * h) { grey(10) }
        src[10 * w + 10] = grey(255)
        val out = Vhs.process(src, w, h, clean.copy(ccdSmear = 1f), seed = 1, frame = 0)
        val inColumn = y(out[25 * w + 10])
        val beside = y(out[25 * w + 14])
        val far = y(out[25 * w + 30])
        assertTrue(inColumn > beside + 8, "column under the light ($inColumn) should glow vs beside ($beside)")
        assertEquals(far, beside, 2.0, "smear stays in its column")
        val above = y(out[2 * w + 10])
        assertTrue(above > beside + 8, "smear runs the full height, above the light too ($above)")
    }

    @Test
    fun `tape noise is animated but reproducible`() {
        val w = 64
        val h = 48
        val src = IntArray(w * h) { grey(120) }
        val s = VhsSettings()
        val f1 = Vhs.process(src, w, h, s, seed = 11, frame = 1)
        val f2 = Vhs.process(src, w, h, s, seed = 11, frame = 2)
        val f1again = Vhs.process(src, w, h, s, seed = 11, frame = 1)
        assertFalse(f1.contentEquals(f2), "noise should change between frames")
        assertContentEquals(f1, f1again, "same seed and frame must reproduce")
        assertFalse(f1.contentEquals(src), "default settings must actually add noise")
    }

    @Test
    fun `tiny frames are safe`() {
        val everything = VhsSettings(
            lumaNoise = 0.2f, chromaNoise = 0.5f, snow = 0.05f, dropouts = 1f, jitterPx = 3f,
            headSwitchLines = 8, tracking = 1f, ccdSmear = 1f,
        )
        for ((w, h) in listOf(0 to 0, 1 to 1, 2 to 9, 9 to 2)) {
            val out = Vhs.process(IntArray(w * h) { grey(200) }, w, h, everything, seed = 1, frame = 3)
            assertEquals(w * h, out.size)
            assertTrue(out.all { it ushr 24 == 0xFF })
        }
    }
}
