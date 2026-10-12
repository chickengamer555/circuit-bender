package com.chickengamer.circuitbender.core

import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class V8Test {
    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    /** A soft, photo-like chart: smooth colour gradients. */
    private fun chart(w: Int, h: Int) = IntArray(w * h) {
        val x = it % w
        val y = it / w
        rgb(60 + x * 140 / w, 80 + y * 120 / h, 150 - (x + y) * 90 / (w + h))
    }

    /** Mean chroma (R-Y, B-Y) difference: how far the colours moved. */
    private fun colourShift(a: IntArray, b: IntArray): Double = a.indices.sumOf { i ->
        fun cr(c: Int) = ((c shr 16) and 0xFF) - luma(c)
        fun cb(c: Int) = (c and 0xFF) - luma(c)
        (abs(cr(a[i]) - cr(b[i])) + abs(cb(a[i]) - cb(b[i]))).toDouble()
    } / a.size

    @Test
    fun `colour knob responds across its range`() {
        val w = 160
        val h = 120
        val src = chart(w, h)
        val levels = listOf(0.1f, 0.25f, 0.5f, 0.75f, 1f)
        val shifts = levels.map { k -> colourShift(src, BendEngine(7).process(src, w, h, Knobs.bend(colour = k), 3)) }
        for (i in 1 until shifts.size) {
            assertTrue(shifts[i] > shifts[i - 1], "colour at ${levels[i]} (${shifts[i]}) should beat ${levels[i - 1]} (${shifts[i - 1]})")
        }
        assertTrue(shifts[0] > 3.0, "10% should already be visible, was ${shifts[0]}")
        assertTrue(shifts[1] >= shifts.last() * 0.25, "25% (${shifts[1]}) should reach a quarter of 100% (${shifts.last()})")
    }

    @Test
    fun `chroma lines leave brightness alone`() {
        val w = 32
        val h = 8
        val grey = IntArray(w * h) { val v = 30 + it % 200; rgb(v, v, v) }
        val bytes = Bus.pack(grey, w, h)
        val before = bytes.copyOf()
        val lines = List(8) { DataLine(if (it % 2 == 0) LineMode.HIGH else LineMode.LOW, pot = 0f) }
        Bus.applyChromaLines(bytes, lines, Random(1))
        for (i in bytes.indices) {
            if (i % 2 == 0) assertEquals(before[i], bytes[i], "brightness byte $i changed")
        }
        assertTrue((1 until bytes.size step 2).any { bytes[it] != before[it] }, "colour bytes were bent")
    }
}
