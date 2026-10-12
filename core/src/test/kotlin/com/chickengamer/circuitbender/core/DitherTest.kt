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

    /**
     * Published kernels from docs/RESEARCH.md 3.2, typed in independently of
     * Dither.kt: (dx, dy, weight) and divisor.
     */
    private val published = mapOf(
        DitherAlgorithm.FLOYD_STEINBERG to (16 to listOf(Triple(1, 0, 7), Triple(-1, 1, 3), Triple(0, 1, 5), Triple(1, 1, 1))),
        DitherAlgorithm.ATKINSON to (8 to listOf(Triple(1, 0, 1), Triple(2, 0, 1), Triple(-1, 1, 1), Triple(0, 1, 1), Triple(1, 1, 1), Triple(0, 2, 1))),
        DitherAlgorithm.JARVIS_JUDICE_NINKE to (48 to listOf(
            Triple(1, 0, 7), Triple(2, 0, 5),
            Triple(-2, 1, 3), Triple(-1, 1, 5), Triple(0, 1, 7), Triple(1, 1, 5), Triple(2, 1, 3),
            Triple(-2, 2, 1), Triple(-1, 2, 3), Triple(0, 2, 5), Triple(1, 2, 3), Triple(2, 2, 1),
        )),
        DitherAlgorithm.STUCKI to (42 to listOf(
            Triple(1, 0, 8), Triple(2, 0, 4),
            Triple(-2, 1, 2), Triple(-1, 1, 4), Triple(0, 1, 8), Triple(1, 1, 4), Triple(2, 1, 2),
            Triple(-2, 2, 1), Triple(-1, 2, 2), Triple(0, 2, 4), Triple(1, 2, 2), Triple(2, 2, 1),
        )),
        DitherAlgorithm.BURKES to (32 to listOf(
            Triple(1, 0, 8), Triple(2, 0, 4),
            Triple(-2, 1, 2), Triple(-1, 1, 4), Triple(0, 1, 8), Triple(1, 1, 4), Triple(2, 1, 2),
        )),
        DitherAlgorithm.SIERRA to (32 to listOf(
            Triple(1, 0, 5), Triple(2, 0, 3),
            Triple(-2, 1, 2), Triple(-1, 1, 4), Triple(0, 1, 5), Triple(1, 1, 4), Triple(2, 1, 2),
            Triple(-1, 2, 2), Triple(0, 2, 3), Triple(1, 2, 2),
        )),
        DitherAlgorithm.SIERRA_LITE to (4 to listOf(Triple(1, 0, 2), Triple(-1, 1, 1), Triple(0, 1, 1))),
    )

    /** Textbook serpentine error diffusion to black/white, the oracle. */
    private fun reference(src: IntArray, w: Int, h: Int, divisor: Int, taps: List<Triple<Int, Int, Int>>): IntArray {
        val buf = FloatArray(src.size) { luma(src[it]) }
        val out = IntArray(src.size)
        for (y in 0 until h) {
            val ltr = y % 2 == 0
            for (step in 0 until w) {
                val x = if (ltr) step else w - 1 - step
                val i = y * w + x
                val v = buf[i].coerceIn(-128f, 383f)
                val on = kotlin.math.abs(v - 255f) < kotlin.math.abs(v)
                out[i] = if (on) white else black
                val err = v - if (on) 255f else 0f
                for ((dx, dy, wt) in taps) {
                    val nx = x + if (ltr) dx else -dx
                    val ny = y + dy
                    if (nx in 0 until w && ny < h) buf[ny * w + nx] += err * (wt / divisor.toFloat())
                }
            }
        }
        return out
    }

    @Test
    fun `error diffusion matches published kernels`() {
        val rnd = Random(7)
        val w = 64
        val h = 48
        val src = IntArray(w * h) { grey(rnd.nextInt(256)) }
        for ((algo, kernel) in published) {
            val (divisor, taps) = kernel
            val expected = reference(src, w, h, divisor, taps)
            val actual = Dither.apply(src, w, h, algo, oneBit)
            val mismatches = expected.indices.count { expected[it] != actual[it] }
            // Float summation order may differ in rare ties; a wrong weight
            // anywhere changes a large share of the picture.
            assertTrue(mismatches <= src.size / 500, "$algo differs from the published kernel at $mismatches px")
        }
    }

    @Test
    fun `bayer4 grey sweep lights cells in matrix order`() {
        // Level k/16 must light exactly the k cells with the highest
        // thresholds: index >= 16 - k (docs/RESEARCH.md 3.1).
        val m = Dither.bayerMatrix(4)
        for (k in 1..15) {
            val v = (k * 255 + 8) / 16
            val out = Dither.apply(flat(4, 4, v), 4, 4, DitherAlgorithm.BAYER_4, oneBit)
            for (cell in 0 until 16) {
                val expectWhite = m[cell] >= 16 - k
                assertEquals(expectWhite, out[cell] == white, "level $k/16 (grey $v) cell $cell (index ${m[cell]})")
            }
        }
    }

    @Test
    fun `game boy camera thresholds match research`() {
        // (r0, r1, r2) per cell, row-major, from docs/RESEARCH.md 3.4.
        val table = listOf(
            Triple(42, 94, 155), Triple(81, 139, 202), Triple(51, 105, 166), Triple(90, 151, 214),
            Triple(68, 124, 186), Triple(55, 109, 170), Triple(77, 135, 198), Triple(64, 120, 182),
            Triple(48, 101, 162), Triple(87, 147, 210), Triple(45, 97, 158), Triple(84, 143, 206),
            Triple(74, 132, 194), Triple(61, 116, 178), Triple(71, 128, 190), Triple(58, 113, 174),
        )
        val shades = Palettes.GAME_BOY_POCKET // 0x00, 0x55, 0xAA, 0xFF
        fun shadeAt(cell: Int, v: Int): Int {
            val out = Dither.apply(flat(4, 4, v), 4, 4, DitherAlgorithm.GAME_BOY_CAMERA, shades)
            return (out[cell] and 0xFF) / 0x55
        }
        for ((cell, t) in table.withIndex()) {
            val (r0, r1, r2) = t
            assertEquals(0, shadeAt(cell, r0 - 1), "cell $cell below r0")
            assertEquals(1, shadeAt(cell, r0), "cell $cell at r0")
            assertEquals(1, shadeAt(cell, r1 - 1), "cell $cell below r1")
            assertEquals(2, shadeAt(cell, r1), "cell $cell at r1")
            assertEquals(2, shadeAt(cell, r2 - 1), "cell $cell below r2")
            assertEquals(3, shadeAt(cell, r2), "cell $cell at r2")
        }
    }
}
