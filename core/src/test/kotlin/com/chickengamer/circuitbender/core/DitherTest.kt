package com.chickengamer.circuitbender.core

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DitherTest {
    private val black = 0xFF000000.toInt()
    private val white = 0xFFFFFFFF.toInt()
    private val oneBit = Palette("test 1-bit", intArrayOf(black, white))

    private fun grey(v: Int) = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
    private fun flat(w: Int, h: Int, v: Int) = IntArray(w * h) { grey(v) }
    private fun whiteFraction(px: IntArray) = px.count { it == white }.toDouble() / px.size

    private val errorDiffusers = listOf(
        DitherAlgorithm.FLOYD_STEINBERG,
        DitherAlgorithm.JARVIS_JUDICE_NINKE,
        DitherAlgorithm.STUCKI,
        DitherAlgorithm.SIERRA,
    )

    @Test
    fun `output uses only palette colours`() {
        val rnd = Random(42)
        val w = 37
        val h = 23
        val src = IntArray(w * h) { (0xFF shl 24) or rnd.nextInt(0x1000000) }
        val palettes = Palettes.ALL + oneBit
        for (palette in palettes) {
            val allowed = palette.colors.toSet()
            for (algo in DitherAlgorithm.entries) {
                val out = Dither.apply(src, w, h, algo, palette)
                assertEquals(src.size, out.size)
                val stray = out.firstOrNull { it !in allowed }
                assertTrue(stray == null, "$algo on ${palette.name} produced off-palette colour ${stray?.let { Integer.toHexString(it) }}")
            }
        }
    }

    @Test
    fun `bayer matrices match the published values`() {
        // Bayer (1973): M(2n) = [[4M, 4M+2],[4M+3, 4M+1]], M(1) = [0].
        assertContentEquals(intArrayOf(0, 2, 3, 1), Dither.bayerMatrix(2))
        assertContentEquals(
            intArrayOf(
                0, 8, 2, 10,
                12, 4, 14, 6,
                3, 11, 1, 9,
                15, 7, 13, 5,
            ),
            Dither.bayerMatrix(4),
        )
        val m8 = Dither.bayerMatrix(8)
        assertEquals(64, m8.size)
        assertEquals((0 until 64).toSet(), m8.toSet(), "8x8 must be a permutation of 0..63")
        // First row of the canonical 8x8 matrix.
        assertContentEquals(intArrayOf(0, 32, 8, 40, 2, 34, 10, 42), m8.copyOfRange(0, 8))
    }

    @Test
    fun `bayer4 half grey is half white`() {
        val w = 16
        val h = 16
        val out = Dither.apply(flat(w, h, 128), w, h, DitherAlgorithm.BAYER_4, oneBit)
        for (ty in 0 until h step 4) for (tx in 0 until w step 4) {
            var whites = 0
            for (y in 0 until 4) for (x in 0 until 4) if (out[(ty + y) * w + tx + x] == white) whites++
            assertEquals(8, whites, "tile at $tx,$ty")
        }
    }

    @Test
    fun `error diffusion preserves average tone`() {
        val w = 120
        val h = 120
        val v = (0.30 * 255).toInt()
        for (algo in errorDiffusers) {
            val frac = whiteFraction(Dither.apply(flat(w, h, v), w, h, algo, oneBit))
            assertEquals(v / 255.0, frac, 0.02, "$algo average tone")
        }
    }

    @Test
    fun `atkinson drops faint tones`() {
        // Atkinson diffuses only 6/8 of the error, so a pixel's accumulated value
        // saturates at 4x the input; below 1/8 grey nothing ever reaches the
        // threshold. Floyd-Steinberg keeps all the error and does produce dots.
        val w = 100
        val h = 100
        val v = (0.10 * 255).toInt()
        val atkinson = whiteFraction(Dither.apply(flat(w, h, v), w, h, DitherAlgorithm.ATKINSON, oneBit))
        val fs = whiteFraction(Dither.apply(flat(w, h, v), w, h, DitherAlgorithm.FLOYD_STEINBERG, oneBit))
        assertEquals(0.0, atkinson, "Atkinson should drop a 10% grey entirely")
        assertEquals(0.10, fs, 0.02, "Floyd-Steinberg should keep a 10% grey")
    }

    @Test
    fun `degenerate inputs are safe`() {
        for (algo in DitherAlgorithm.entries) {
            assertEquals(0, Dither.apply(IntArray(0), 0, 0, algo, oneBit).size)
            val one = Dither.apply(intArrayOf(grey(200)), 1, 1, algo, oneBit)
            assertTrue(one[0] == white || one[0] == black, "$algo 1x1")
            assertTrue(Dither.apply(flat(9, 7, 0), 9, 7, algo, oneBit).all { it == black }, "$algo pure black")
            assertTrue(Dither.apply(flat(9, 7, 255), 9, 7, algo, oneBit).all { it == white }, "$algo pure white")
        }
    }
}
