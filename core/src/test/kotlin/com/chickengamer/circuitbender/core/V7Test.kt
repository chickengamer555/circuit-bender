package com.chickengamer.circuitbender.core

import kotlin.test.Test
import kotlin.test.assertTrue

class V7Test {
    private val w = 640
    private val h = 480
    private val src = IntArray(w * h) { (0xFF shl 24) or (it * 2654435761L.toInt() ushr 8) }

    /** Best of three medians, after warm-up: the honest measure of the code on a busy machine. */
    private fun ms(block: () -> Unit): Double {
        repeat(30) { block() }
        return (0 until 3).minOf {
            val times = (0 until 11).map {
                val t0 = System.nanoTime()
                block()
                (System.nanoTime() - t0) / 1e6
            }.sorted()
            times[times.size / 2]
        }
    }

    @Test
    fun `tape under hi res dither stays cheap`() {
        val s = PipelineSettings(
            ditherEnabled = true, resolution = Resolution.LARGE,
            algorithm = DitherAlgorithm.FLOYD_STEINBERG, palette = Palettes.natural(3),
        )
        val withTape = Pipeline(1)
        val without = Pipeline(1)
        val tape = ms { withTape.process(src, s, null, 0) }
        val bare = ms { without.process(src, s.copy(vhsEnabled = false), null, 0) }
        assertTrue(tape <= bare * 1.6, "tape adds too much: $tape ms with VHS vs $bare ms without")
    }

    @Test
    fun `monochrome diffusion is not slower than colour`() {
        val mono = ms { Dither.apply(src, w, h, DitherAlgorithm.FLOYD_STEINBERG, Palettes.ONE_BIT) }
        val colour = ms { Dither.apply(src, w, h, DitherAlgorithm.FLOYD_STEINBERG, Palettes.natural(3)) }
        assertTrue(mono <= colour * 1.1, "1-bit $mono ms vs 27 colours $colour ms")
    }
}
