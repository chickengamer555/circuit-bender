package com.chickengamer.circuitbender.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class V6Test {
    @Test
    fun `steady frames reuse their buffers`() {
        val s = PipelineSettings(
            ditherEnabled = true, resolution = Resolution.LARGE,
            algorithm = DitherAlgorithm.FLOYD_STEINBERG, palette = Palettes.natural(3),
        )
        val n = s.width * s.height
        val src = IntArray(n) { (0xFF shl 24) or (it * 2654435761L.toInt() ushr 8) }
        val p = Pipeline(1)
        val a = p.process(src, s, null, 0)
        val aPixels = a.copyOf()
        val b = p.process(src, s, null, 1)
        val c = p.process(src, s, null, 2)
        assertNotSame(a, b, "consecutive frames must not share a buffer")
        assertSame(a, c, "every other frame reuses the same buffer")
        // Frame 0 had to survive while frame 1 was being made.
        val p2 = Pipeline(1)
        val first = p2.process(src, s, null, 0)
        val firstCopy = first.copyOf()
        p2.process(src, s, null, 1)
        assertContentEquals(firstCopy, first, "the previous frame stays intact until the next is done")
        assertContentEquals(aPixels, firstCopy)
    }

    @Test
    fun `pacer caps rate and busy time and backs off when hot`() {
        // A camera offering a frame on a fixed 60 fps clock for 10 s; frames
        // that arrive while one is being processed are dropped, as with
        // CameraX's keep-only-latest.
        fun run(costMs: Long, thermal: Int): Pair<Int, Double> {
            val pacer = FramePacer(maxFps = 30, maxBusy = 0.6f)
            pacer.thermal = thermal
            var processed = 0
            var busy = 0L
            var freeAt = 0L
            for (k in 0 until 600) {
                val t = (k * 1000L + 30) / 60
                if (t < freeAt || !pacer.shouldProcess(t)) continue
                processed++
                busy += costMs
                pacer.finished(t, costMs)
                freeAt = t + costMs
            }
            return processed to busy / 10_000.0
        }
        val (cheap, _) = run(5, 0)
        assertTrue(cheap in 280..305, "cheap frames are capped at 30 fps, got ${cheap / 10.0} fps")
        val (heavy, share) = run(40, 0)
        assertTrue(share <= 0.62, "heavy frames keep busy time <= 60%, got $share")
        assertTrue(heavy >= 120, "but still run as often as that allows, got ${heavy / 10.0} fps")
        val (warm, _) = run(5, FramePacer.MODERATE)
        assertTrue(warm in 140..155, "moderate heat halves the cap, got ${warm / 10.0} fps")
        val (hot, _) = run(5, FramePacer.SEVERE)
        assertTrue(hot in 70..78, "severe heat quarters it, got ${hot / 10.0} fps")
    }
}
