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

    /** Hue sextant 0..5 (red, yellow, green, cyan, blue, magenta), or -1 for greys. */
    private fun sextant(c: Int): Int {
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        if (max - min < 40) return -1
        val d = (max - min).toFloat()
        val h = when (max) {
            r -> ((g - b) / d + 6f) % 6f
            g -> (b - r) / d + 2f
            else -> (r - g) / d + 4f
        }
        return ((h + 0.5f).toInt()) % 6
    }

    /** Every hue across, darker to lighter down: no colour family favoured. */
    private fun hueWheel(w: Int, h: Int) = IntArray(w * h) {
        val hue = (it % w) * 6f / w
        val v = 60 + (it / w) * 150 / h
        val s = 0.45f
        val f = hue - hue.toInt()
        val p = (v * (1 - s)).toInt()
        val q = (v * (1 - s * f)).toInt()
        val t = (v * (1 - s * (1 - f))).toInt()
        when (hue.toInt() % 6) {
            0 -> rgb(v, t, p)
            1 -> rgb(q, v, p)
            2 -> rgb(p, v, t)
            3 -> rgb(p, q, v)
            4 -> rgb(t, p, v)
            else -> rgb(v, p, q)
        }
    }

    @Test
    fun `colour bend gives many false colours not one tint`() {
        val w = 160
        val h = 120
        // A balanced chart: amplifying a scene's own colour is expected (green
        // grass goes greener), so only a chart with every hue can tell a
        // genuine one-way tint from that.
        val src = hueWheel(w, h)
        for (k in listOf(0.3f, 0.6f)) {
            val out = BendEngine(7).process(src, w, h, Knobs.bend(colour = k), 3)
            // Which way did each pixel's colour move? A tint moves every pixel
            // the same way; real bends move them in many directions.
            val dirs = IntArray(6)
            for (i in src.indices) {
                fun cr(c: Int) = ((c shr 16) and 0xFF) - luma(c)
                fun cb(c: Int) = (c and 0xFF) - luma(c)
                val dr = cr(out[i]) - cr(src[i])
                val db = cb(out[i]) - cb(src[i])
                if (abs(dr) + abs(db) < 16) continue
                val angle = Math.toDegrees(kotlin.math.atan2(dr.toDouble(), db.toDouble())) + 360.0
                dirs[(angle / 60.0).toInt() % 6]++
            }
            val total = dirs.sum().coerceAtLeast(1)
            assertTrue(dirs.max() <= total * 0.45, "colour $k: colours all shift one way ${dirs.toList()} - a single tint")
            assertTrue(dirs.count { it >= total * 0.1 } >= 3, "colour $k: colour shifts go too few ways ${dirs.toList()}")
            // Real bends pair opposites: some pixels must swing toward green
            // (both colour differences down) as others swing toward magenta.
            var toGreen = 0
            var toMagenta = 0
            for (i in src.indices) {
                fun cr(c: Int) = ((c shr 16) and 0xFF) - luma(c)
                fun cb(c: Int) = (c and 0xFF) - luma(c)
                val dr = cr(out[i]) - cr(src[i])
                val db = cb(out[i]) - cb(src[i])
                if (dr < -8 && db < -8) toGreen++
                if (dr > 8 && db > 8) toMagenta++
            }
            assertTrue(toGreen >= total * 0.15, "colour $k: almost nothing swings toward green ($toGreen of $total) - one-sided tint")
            assertTrue(toMagenta >= total * 0.15, "colour $k: almost nothing swings toward magenta ($toMagenta of $total)")
        }
        // Full: shadows go green, highlights magenta (docs/COLOUR-RESEARCH.md).
        val ramp = IntArray(w * h) { val v = 20 + (it % w) * 215 / w; rgb(v, v, v) }
        val out = BendEngine(7).process(ramp, w, h, Knobs.bend(colour = 1f), 3)
        fun lean(px: List<Int>) = px.map { c -> ((c shr 16) and 0xFF) + (c and 0xFF) - 2 * ((c shr 8) and 0xFF) }.average()
        val dark = (0 until w * h).filter { it % w < w / 4 }.map { out[it] }
        val bright = (0 until w * h).filter { it % w > w * 3 / 4 }.map { out[it] }
        assertTrue(lean(dark) < -10, "shadows should lean green (R+B-2G = ${lean(dark)})")
        assertTrue(lean(bright) > 10, "highlights should lean magenta (R+B-2G = ${lean(bright)})")
    }
}
