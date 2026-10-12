package com.chickengamer.circuitbender.core

import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class V3Test {
    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    private fun grey(v: Int) = rgb(v, v, v)
    private fun y(c: Int) = luma(c).toDouble()

    private fun chart(w: Int, h: Int) = IntArray(w * h) {
        val x = it % w
        val yy = it / w
        rgb((x * 255) / maxOf(1, w - 1), (yy * 255) / maxOf(1, h - 1), ((x + yy) * 5) and 0xFF)
    }

    private fun meanDiff(a: IntArray, b: IntArray): Double = a.indices.sumOf { i ->
        val p = a[i]
        val q = b[i]
        (abs(((p shr 16) and 0xFF) - ((q shr 16) and 0xFF)) + abs(((p shr 8) and 0xFF) - ((q shr 8) and 0xFF)) +
            abs((p and 0xFF) - (q and 0xFF))).toDouble()
    } / a.size

    @Test
    fun `camcorder frames are analog shaped and saved smooth`() {
        val s = PipelineSettings()
        assertEquals(320, s.width)
        assertEquals(480, s.height)
        assertEquals(4 to 3, s.displayWidth to s.displayHeight)
        val up = PipelineSettings(portrait = true)
        assertEquals(240, up.width)
        assertEquals(640, up.height)
        assertEquals(3 to 4, up.displayWidth to up.displayHeight)

        // Steep sawtooth: 7 levels per sample, so smooth scaling never repeats
        // a value 4 times in a row while hard-edged 4x scaling always does.
        val ramp = IntArray(s.width * s.height) { grey((it % s.width) * 7 % 256) }
        val p = Pipeline(1)
        p.process(ramp, s.copy(vhsEnabled = false), null, 0, clean = true)
        val photo = p.capturePhoto(1280)
        assertEquals(1280, photo.width)
        assertEquals(960, photo.height)
        val row = photo.pixels.copyOfRange(480 * 1280, 481 * 1280)
        var longest = 1
        var run = 1
        for (i in 1 until row.size) {
            run = if (row[i] == row[i - 1]) run + 1 else 1
            longest = maxOf(longest, run)
        }
        assertTrue(longest < 4, "photo should be smoothly scaled, found a flat run of $longest")
    }

    @Test
    fun `blacks lift and whites roll off`() {
        val clean = VhsSettings().noiseless()
        val black = Vhs.process(IntArray(64 * 48) { grey(0) }, 64, 48, clean, 1, 0)
        val white = Vhs.process(IntArray(64 * 48) { grey(255) }, 64, 48, clean, 1, 0)
        val b = y(black[24 * 64 + 32])
        val w = y(white[24 * 64 + 32])
        assertTrue(b in 10.0..30.0, "black should lift to grey, was $b")
        assertTrue(w < 245.0 && w > 190.0, "white should roll off, was $w")
    }

    @Test
    fun `bright lights glow`() {
        val w = 64
        val h = 48
        val src = IntArray(w * h) { grey(10) }
        for (yy in 20 until 28) for (x in 28 until 36) src[yy * w + x] = grey(255)
        val glow = Vhs.process(src, w, h, VhsSettings().noiseless(), 1, 0)
        val none = Vhs.process(src, w, h, VhsSettings().noiseless().copy(bloom = 0f), 1, 0)
        // 3 px above the light, where horizontal smear cannot reach.
        val i = 17 * w + 32
        assertTrue(y(glow[i]) > y(none[i]) + 4, "glow above the light: ${y(glow[i])} vs ${y(none[i])}")
    }

    @Test
    fun `grain is heavier in shadows`() {
        val w = 96
        val h = 64
        val s = VhsSettings().noiseless().copy(lumaNoise = 0.01f)
        fun spread(v: Int): Double {
            val out = Vhs.process(IntArray(w * h) { grey(v) }, w, h, s, 1, 4)
            val ys = out.map(::y)
            val m = ys.average()
            return sqrt(ys.sumOf { (it - m) * (it - m) } / ys.size)
        }
        val dark = spread(40)
        val bright = spread(200)
        assertTrue(dark > bright * 1.3, "shadow grain $dark should beat highlight grain $bright")
    }

    @Test
    fun `natural colour dither keeps the colour`() {
        val nat = Palettes.natural(4)
        assertEquals(64, nat.colors.size)
        assertEquals(64, nat.colors.toSet().size)
        val w = 48
        val h = 48
        val orange = rgb(230, 120, 40)
        for (algo in listOf(DitherAlgorithm.BAYER_4, DitherAlgorithm.FLOYD_STEINBERG)) {
            val out = Dither.apply(IntArray(w * h) { orange }, w, h, algo, nat)
            val allowed = nat.colors.toSet()
            assertTrue(out.all { it in allowed }, "$algo stays in palette")
            val r = out.map { (it shr 16) and 0xFF }.average()
            val g = out.map { (it shr 8) and 0xFF }.average()
            val b = out.map { it and 0xFF }.average()
            assertTrue(abs(r - 230) < 8 && abs(g - 120) < 8 && abs(b - 40) < 8, "$algo average ($r, $g, $b) should stay orange")
        }
    }

    @Test
    fun `dither knobs reach every option`() {
        val steps = (0..200).map { it / 200f }
        assertEquals(DitherAlgorithm.entries.toSet(), steps.map(Knobs::ditherStyle).toSet())
        val palettes = steps.map(Knobs::palette).distinct()
        assertTrue(palettes.size >= 12, "only ${palettes.size} colour choices")
        assertTrue(palettes.any { it.colors.size >= 64 && !it.monochrome }, "a natural-colour choice is reachable")
        val sizes = steps.map(Knobs::pixel).distinct()
        assertTrue(sizes.size >= 6, "only ${sizes.size} sizes")
        assertEquals(640, sizes.maxOf { it.width })
        assertEquals(64, sizes.minOf { it.width })

        val base = PipelineSettings(ditherEnabled = true, vhsEnabled = false, palette = Palettes.ONE_BIT)
        val src = chart(base.width, base.height)
        val low = Pipeline(1).process(src, base.copy(ditherContrast = Knobs.contrast(0f)), null, 0)
        val high = Pipeline(1).process(src, base.copy(ditherContrast = Knobs.contrast(1f)), null, 0)
        assertTrue(meanDiff(low, high) > 10, "contrast knob must change the dither")
    }

    @Test
    fun `six bend knobs each get stronger`() {
        assertTrue(Knobs.bend().isNeutral)
        val w = 160
        val h = 120
        val src = chart(w, h)
        val knobs = mapOf<String, (Float) -> BendSettings>(
            "NOISE" to { Knobs.bend(noise = it) },
            "TEAR" to { Knobs.bend(tear = it) },
            "COLOUR" to { Knobs.bend(colour = it) },
            "MEMORY" to { Knobs.bend(memory = it) },
            "ROLL" to { Knobs.bend(roll = it) },
            "BLOCKS" to { Knobs.bend(blocks = it) },
        )
        for ((name, at) in knobs) {
            // A few frames in, so memory and roll have had time to act.
            // Alternate two pictures so stale memory has something to show,
            // and measure the last output against the last input.
            val flipped = src.reversedArray()
            fun run(k: Float): Double {
                val e = BendEngine(3)
                var out = src
                for (f in 0L until 6L) out = e.process(if (f % 2 == 1L) flipped else src, w, h, at(k), f)
                return meanDiff(flipped, out)
            }
            val low = run(0.3f)
            val high = run(1f)
            assertTrue(low > 0.5, "$name at 0.3 should change the picture ($low)")
            assertTrue(high > low * 1.2, "$name at 1.0 ($high) should be stronger than at 0.3 ($low)")
        }
    }

    @Test
    fun `memory bend never freezes the picture`() {
        val sizes = listOf(320 to 480, 240 to 640, 320 to 240, 160 to 120, 128 to 112, 64 to 48)
        for ((w, h) in sizes) for (m in listOf(0.2f, 1f)) {
            val e = BendEngine(5)
            val s = Knobs.bend(memory = m)
            val rnd = Random(1)
            var last = IntArray(0)
            var changes = 0
            for (f in 0L until 12L) {
                val frame = IntArray(w * h) { grey(rnd.nextInt(256)) }
                val out = e.process(frame, w, h, s, f)
                if (f > 0 && meanDiff(out, last) > 1.0) changes++
                last = out
            }
            assertTrue(changes >= 8, "${w}x$h memory $m: output changed on only $changes of 11 new frames")
        }
    }
}
