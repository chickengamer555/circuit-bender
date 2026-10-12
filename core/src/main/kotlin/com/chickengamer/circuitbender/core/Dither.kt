package com.chickengamer.circuitbender.core

import kotlin.math.roundToInt

/**
 * A fixed set of output colours (opaque ARGB).
 *
 * [monochrome] palettes (1-bit, Game Boy) are matched on brightness alone, the
 * way the real hardware worked: a Game Boy shade is a luminance level that the
 * LCD happens to tint green. Colour palettes are matched in RGB.
 *
 * [spread] is the amplitude of the ordered-dither offset, 255/(levels-1) for an
 * evenly spaced palette (docs/RESEARCH.md 3.1).
 */
class Palette(
    val name: String,
    val colors: IntArray,
    val monochrome: Boolean = colors.all { isGrey(it) },
    val spread: Float = 255f / maxOf(1, colors.size - 1),
    /** > 0 for a natural palette: this many evenly spaced levels per channel. */
    val levels: Int = 0,
) {
    /** Palette indices sorted darkest first, and their luma. */
    internal val byLuma: IntArray = colors.indices.sortedBy { luma(colors[it]) }.toIntArray()
    internal val lumas: FloatArray = FloatArray(colors.size) { luma(colors[byLuma[it]]) }
    internal val r = FloatArray(colors.size) { ((colors[it] shr 16) and 0xFF).toFloat() }
    internal val g = FloatArray(colors.size) { ((colors[it] shr 8) and 0xFF).toFloat() }
    internal val b = FloatArray(colors.size) { (colors[it] and 0xFF).toFloat() }

    /**
     * Nearest-colour table over RGB565 cells, built on first use, so colour
     * matching is one lookup per pixel instead of a search of the palette.
     */
    /** Output colour for each half-unit brightness bucket, for monochrome palettes. */
    internal val lumaLut: IntArray by lazy {
        IntArray(Dither.LUMA_BUCKETS) { Dither.nearestLumaColor(this, it / 2f - 256f + 0.25f) }
    }

    internal val rgbLut: ByteArray by lazy {
        ByteArray(32 * 64 * 32).also { lut ->
            for (ri in 0 until 32) for (gi in 0 until 64) for (bi in 0 until 32) {
                lut[(ri shl 11) or (gi shl 5) or bi] = Dither.nearestRgbExact(
                    this, ri * 8 + 4f, gi * 4 + 2f, bi * 8 + 4f,
                ).toByte()
            }
        }
    }

    private companion object {
        fun isGrey(c: Int): Boolean {
            val r = (c shr 16) and 0xFF
            return r == ((c shr 8) and 0xFF) && r == (c and 0xFF)
        }
    }
}

/** Rec. 601 luma, 0..255. */
internal fun luma(c: Int): Float =
    0.299f * ((c shr 16) and 0xFF) + 0.587f * ((c shr 8) and 0xFF) + 0.114f * (c and 0xFF)

private fun rgb(hex: Int) = hex or (0xFF shl 24)

/** Exact palettes from docs/RESEARCH.md 3.5. */
object Palettes {
    val ONE_BIT = Palette("1-bit Mac", intArrayOf(rgb(0x000000), rgb(0xFFFFFF)))
    val GAME_BOY = Palette(
        "Game Boy DMG",
        intArrayOf(rgb(0x0F380F), rgb(0x306230), rgb(0x8BAC0F), rgb(0x9BBC0F)),
        monochrome = true,
    )
    val GAME_BOY_BGB = Palette(
        "Game Boy BGB",
        intArrayOf(rgb(0x081820), rgb(0x346856), rgb(0x88C070), rgb(0xE0F8D0)),
        monochrome = true,
    )
    val GAME_BOY_POCKET = Palette(
        "Game Boy Pocket",
        intArrayOf(rgb(0x000000), rgb(0x555555), rgb(0xAAAAAA), rgb(0xFFFFFF)),
    )
    val CGA_1_HIGH = Palette(
        "CGA mode 4 pal 1 high",
        intArrayOf(rgb(0x000000), rgb(0x55FFFF), rgb(0xFF55FF), rgb(0xFFFFFF)),
        spread = 128f,
    )
    val CGA_0_HIGH = Palette(
        "CGA mode 4 pal 0 high",
        intArrayOf(rgb(0x000000), rgb(0x55FF55), rgb(0xFF5555), rgb(0xFFFF55)),
        spread = 128f,
    )
    val CGA_5_HIGH = Palette(
        "CGA mode 5 high",
        intArrayOf(rgb(0x000000), rgb(0x55FFFF), rgb(0xFF5555), rgb(0xFFFFFF)),
        spread = 128f,
    )
    val EGA_16 = Palette(
        "CGA/EGA 16",
        intArrayOf(
            rgb(0x000000), rgb(0x0000AA), rgb(0x00AA00), rgb(0x00AAAA),
            rgb(0xAA0000), rgb(0xAA00AA), rgb(0xAA5500), rgb(0xAAAAAA),
            rgb(0x555555), rgb(0x5555FF), rgb(0x55FF55), rgb(0x55FFFF),
            rgb(0xFF5555), rgb(0xFF55FF), rgb(0xFFFF55), rgb(0xFFFFFF),
        ),
        spread = 85f,
    )
    val PICO_8 = Palette(
        "PICO-8",
        intArrayOf(
            rgb(0x000000), rgb(0x1D2B53), rgb(0x7E2553), rgb(0x008751),
            rgb(0xAB5236), rgb(0x5F574F), rgb(0xC2C3C7), rgb(0xFFF1E8),
            rgb(0xFF004D), rgb(0xFFA300), rgb(0xFFFF27), rgb(0x00E756),
            rgb(0x29ADFF), rgb(0x83769C), rgb(0xFF77A8), rgb(0xFFCCAA),
        ),
        spread = 64f,
    )

    /**
     * Natural colour: [levels] evenly spaced values per channel, so the
     * picture keeps its own colours with levels^3 of them.
     */
    fun natural(levels: Int): Palette {
        val n = levels.coerceIn(2, 16)
        val step = 255f / (n - 1)
        val colors = IntArray(n * n * n) {
            val r = (it / (n * n) * step + 0.5f).toInt()
            val g = (it / n % n * step + 0.5f).toInt()
            val b = (it % n * step + 0.5f).toInt()
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return Palette("${n * n * n} colours", colors, monochrome = false, spread = step, levels = n)
    }

    /** Commodore 64, Pepto's measured palette. */
    val C64 = Palette(
        "C64",
        intArrayOf(
            rgb(0x000000), rgb(0xFFFFFF), rgb(0x68372B), rgb(0x70A4B2),
            rgb(0x6F3D86), rgb(0x588D43), rgb(0x352879), rgb(0xB8C76F),
            rgb(0x6F4F25), rgb(0x433900), rgb(0x9A6759), rgb(0x444444),
            rgb(0x6C6C6C), rgb(0x9AD284), rgb(0x6C5EB5), rgb(0x959595),
        ),
        spread = 64f,
    )

    /** ZX Spectrum: 8 colours at normal and bright intensity (black once). */
    val ZX_SPECTRUM = Palette(
        "ZX Spectrum",
        intArrayOf(
            rgb(0x000000), rgb(0x0000D7), rgb(0xD70000), rgb(0xD700D7),
            rgb(0x00D700), rgb(0x00D7D7), rgb(0xD7D700), rgb(0xD7D7D7),
            rgb(0x0000FF), rgb(0xFF0000), rgb(0xFF00FF), rgb(0x00FF00),
            rgb(0x00FFFF), rgb(0xFFFF00), rgb(0xFFFFFF),
        ),
        spread = 96f,
    )

    /** Virtual Boy: four reds on black. */
    val VIRTUAL_BOY = Palette(
        "Virtual Boy",
        intArrayOf(rgb(0x000000), rgb(0x550000), rgb(0xAA0000), rgb(0xFF0000)),
        monochrome = true,
    )

    val SEPIA = Palette(
        "Sepia",
        intArrayOf(rgb(0x2B1D0E), rgb(0x6B4A2B), rgb(0xB08A5A), rgb(0xF1DEB3)),
        monochrome = true,
    )

    val ALL: List<Palette> = listOf(
        ONE_BIT, GAME_BOY, GAME_BOY_BGB, GAME_BOY_POCKET,
        CGA_1_HIGH, CGA_0_HIGH, CGA_5_HIGH, EGA_16, PICO_8,
        C64, ZX_SPECTRUM, VIRTUAL_BOY, SEPIA,
    )
}

enum class DitherAlgorithm(val label: String) {
    BAYER_2("Bayer 2x2"),
    BAYER_4("Bayer 4x4"),
    BAYER_8("Bayer 8x8"),
    CLUSTER_DOT("Halftone"),
    GAME_BOY_CAMERA("Game Boy Camera"),
    FLOYD_STEINBERG("Floyd-Steinberg"),
    ATKINSON("Atkinson"),
    JARVIS_JUDICE_NINKE("Jarvis-Judice-Ninke"),
    STUCKI("Stucki"),
    BURKES("Burkes"),
    SIERRA("Sierra"),
    SIERRA_LITE("Sierra Lite"),
}

/** An error-diffusion kernel: (dx, dy, weight) triples, and the divisor. */
private class Kernel(val divisor: Float, vararg val taps: Int)

object Dither {
    /** Bayer index matrix of size n x n (n a power of two), row-major. */
    fun bayerMatrix(n: Int): IntArray {
        require(n >= 1 && n and (n - 1) == 0) { "n must be a power of two" }
        var size = 1
        var m = intArrayOf(0)
        while (size < n) {
            val next = IntArray(size * 2 * size * 2)
            val s2 = size * 2
            for (y in 0 until size) for (x in 0 until size) {
                val v = 4 * m[y * size + x]
                next[y * s2 + x] = v
                next[y * s2 + x + size] = v + 2
                next[(y + size) * s2 + x] = v + 3
                next[(y + size) * s2 + x + size] = v + 1
            }
            m = next
            size = s2
        }
        return m
    }

    private val BAYER2 = bayerMatrix(2)
    private val BAYER4 = bayerMatrix(4)
    private val BAYER8 = bayerMatrix(8)

    /** Classic 4x4 clustered-dot (halftone) matrix, docs/RESEARCH.md 3.1. */
    private val CLUSTER4 = intArrayOf(12, 5, 6, 13, 4, 0, 1, 7, 11, 3, 2, 8, 15, 10, 9, 14)

    /**
     * Game Boy Camera dither thresholds (r0, r1, r2) per 4x4 cell, row-major,
     * from Raphael Boichot's M64282FP config (docs/RESEARCH.md 3.4).
     */
    val GAME_BOY_CAMERA_MATRIX = intArrayOf(
        42, 94, 155, 81, 139, 202, 51, 105, 166, 90, 151, 214,
        68, 124, 186, 55, 109, 170, 77, 135, 198, 64, 120, 182,
        48, 101, 162, 87, 147, 210, 45, 97, 158, 84, 143, 206,
        74, 132, 194, 61, 116, 178, 71, 128, 190, 58, 113, 174,
    )

    // dx, dy, weight - tannerhelland.com kernels, docs/RESEARCH.md 3.2
    private val FLOYD_STEINBERG = Kernel(16f, 1, 0, 7, -1, 1, 3, 0, 1, 5, 1, 1, 1)
    private val ATKINSON = Kernel(8f, 1, 0, 1, 2, 0, 1, -1, 1, 1, 0, 1, 1, 1, 1, 1, 0, 2, 1)
    private val JJN = Kernel(
        48f,
        1, 0, 7, 2, 0, 5,
        -2, 1, 3, -1, 1, 5, 0, 1, 7, 1, 1, 5, 2, 1, 3,
        -2, 2, 1, -1, 2, 3, 0, 2, 5, 1, 2, 3, 2, 2, 1,
    )
    private val STUCKI = Kernel(
        42f,
        1, 0, 8, 2, 0, 4,
        -2, 1, 2, -1, 1, 4, 0, 1, 8, 1, 1, 4, 2, 1, 2,
        -2, 2, 1, -1, 2, 2, 0, 2, 4, 1, 2, 2, 2, 2, 1,
    )
    private val BURKES = Kernel(
        32f,
        1, 0, 8, 2, 0, 4,
        -2, 1, 2, -1, 1, 4, 0, 1, 8, 1, 1, 4, 2, 1, 2,
    )
    private val SIERRA = Kernel(
        32f,
        1, 0, 5, 2, 0, 3,
        -2, 1, 2, -1, 1, 4, 0, 1, 5, 1, 1, 4, 2, 1, 2,
        -1, 2, 2, 0, 2, 3, 1, 2, 2,
    )
    private val SIERRA_LITE = Kernel(4f, 1, 0, 2, -1, 1, 1, 0, 1, 1)

    /**
     * Dither [src] (opaque ARGB, row-major) to [palette]. Returns [dst], which
     * defaults to a fresh array. [src] is never modified.
     */
    fun apply(
        src: IntArray,
        width: Int,
        height: Int,
        algorithm: DitherAlgorithm,
        palette: Palette,
        dst: IntArray = IntArray(src.size),
    ): IntArray {
        if (width <= 0 || height <= 0) return dst
        when (algorithm) {
            DitherAlgorithm.BAYER_2 -> ordered(src, width, height, BAYER2, 2, palette, dst)
            DitherAlgorithm.BAYER_4 -> ordered(src, width, height, BAYER4, 4, palette, dst)
            DitherAlgorithm.BAYER_8 -> ordered(src, width, height, BAYER8, 8, palette, dst)
            DitherAlgorithm.CLUSTER_DOT -> ordered(src, width, height, CLUSTER4, 4, palette, dst)
            DitherAlgorithm.GAME_BOY_CAMERA -> gameBoyCamera(src, width, height, palette, dst)
            DitherAlgorithm.FLOYD_STEINBERG -> diffuse(src, width, height, FLOYD_STEINBERG, palette, dst)
            DitherAlgorithm.ATKINSON -> diffuse(src, width, height, ATKINSON, palette, dst)
            DitherAlgorithm.JARVIS_JUDICE_NINKE -> diffuse(src, width, height, JJN, palette, dst)
            DitherAlgorithm.STUCKI -> diffuse(src, width, height, STUCKI, palette, dst)
            DitherAlgorithm.BURKES -> diffuse(src, width, height, BURKES, palette, dst)
            DitherAlgorithm.SIERRA -> diffuse(src, width, height, SIERRA, palette, dst)
            DitherAlgorithm.SIERRA_LITE -> diffuse(src, width, height, SIERRA_LITE, palette, dst)
        }
        return dst
    }

    /** Index into palette.byLuma of the level nearest [y]. */
    private fun nearestLuma(p: Palette, y: Float): Int {
        var best = 0
        var bestD = Float.MAX_VALUE
        for (i in p.lumas.indices) {
            val d = kotlin.math.abs(p.lumas[i] - y)
            if (d < bestD) {
                bestD = d
                best = i
            }
        }
        return best
    }

    /** Palette index nearest (r, g, b), via the palette's RGB565 table. */
    private fun nearestRgb(p: Palette, r: Float, g: Float, b: Float): Int {
        if (p.levels > 0) {
            // Natural palette: each channel quantises on its own.
            val n = p.levels
            val k = (n - 1) / 255f
            val ri = (r * k + 0.5f).toInt().coerceIn(0, n - 1)
            val gi = (g * k + 0.5f).toInt().coerceIn(0, n - 1)
            val bi = (b * k + 0.5f).toInt().coerceIn(0, n - 1)
            return (ri * n + gi) * n + bi
        }
        val ri = r.toInt().coerceIn(0, 255) shr 3
        val gi = g.toInt().coerceIn(0, 255) shr 2
        val bi = b.toInt().coerceIn(0, 255) shr 3
        return p.rgbLut[(ri shl 11) or (gi shl 5) or bi].toInt()
    }

    /** Palette index nearest (r, g, b), weighted RGB (3,4,2). */
    internal fun nearestRgbExact(p: Palette, r: Float, g: Float, b: Float): Int {
        var best = 0
        var bestD = Float.MAX_VALUE
        for (i in p.r.indices) {
            val dr = p.r[i] - r
            val dg = p.g[i] - g
            val db = p.b[i] - b
            val d = 3 * dr * dr + 4 * dg * dg + 2 * db * db
            if (d < bestD) {
                bestD = d
                best = i
            }
        }
        return best
    }

    private fun ordered(src: IntArray, w: Int, h: Int, m: IntArray, n: Int, p: Palette, dst: IntArray) {
        val cells = (n * n).toFloat()
        val offsets = FloatArray(m.size) { p.spread * ((m[it] + 0.5f) / cells - 0.5f) }
        val lut = if (p.monochrome) p.lumaLut else null
        for (y in 0 until h) {
            val row = (y % n) * n
            for (x in 0 until w) {
                val t = offsets[row + x % n]
                val c = src[y * w + x]
                dst[y * w + x] = if (lut != null) {
                    lut[lumaIndex(luma(c) + t)]
                } else {
                    p.colors[
                        nearestRgb(
                            p,
                            ((c shr 16) and 0xFF) + t,
                            ((c shr 8) and 0xFF) + t,
                            (c and 0xFF) + t,
                        ),
                    ]
                }
            }
        }
    }

    /** Bucket of [Palette.lumaLut] for a brightness in -256..512, half-unit steps. */
    internal fun lumaIndex(v: Float): Int = ((v + 256f) * 2f).toInt().coerceIn(0, LUMA_BUCKETS - 1)

    internal const val LUMA_BUCKETS = 1536

    internal fun nearestLumaColor(p: Palette, y: Float): Int = p.colors[p.byLuma[nearestLuma(p, y)]]

    /**
     * The Game Boy Camera's per-cell 3-threshold comparison: four shades, the
     * darkest below r0. Shades map onto the palette by brightness.
     */
    private fun gameBoyCamera(src: IntArray, w: Int, h: Int, p: Palette, dst: IntArray) {
        val levels = p.colors.size
        for (y in 0 until h) for (x in 0 until w) {
            val cell = ((y and 3) * 4 + (x and 3)) * 3
            // The cartridge compares 8-bit ADC readings, so compare integers:
            // a float luma of 185.99998 must not miss a threshold of 186.
            val c = src[y * w + x]
            val v = (77 * ((c shr 16) and 0xFF) + 150 * ((c shr 8) and 0xFF) + 29 * (c and 0xFF) + 128) shr 8
            val m = GAME_BOY_CAMERA_MATRIX
            val shade = when {
                v < m[cell] -> 0
                v < m[cell + 1] -> 1
                v < m[cell + 2] -> 2
                else -> 3
            }
            dst[y * w + x] = p.colors[p.byLuma[(shade * (levels - 1) / 3f).roundToInt()]]
        }
    }

    /** Serpentine error diffusion, mirroring the kernel on right-to-left rows. */
    private fun diffuse(src: IntArray, w: Int, h: Int, k: Kernel, p: Palette, dst: IntArray) {
        val channels = if (p.monochrome) 1 else 3
        val buf = Array(channels) { FloatArray(w * h) }
        for (i in src.indices) {
            val c = src[i]
            if (p.monochrome) {
                buf[0][i] = luma(c)
            } else {
                buf[0][i] = ((c shr 16) and 0xFF).toFloat()
                buf[1][i] = ((c shr 8) and 0xFF).toFloat()
                buf[2][i] = (c and 0xFF).toFloat()
            }
        }
        val err = FloatArray(channels)
        val taps = k.taps
        for (y in 0 until h) {
            val ltr = y % 2 == 0
            for (step in 0 until w) {
                val x = if (ltr) step else w - 1 - step
                val i = y * w + x
                val chosen: Int
                if (p.monochrome) {
                    // Clamp so runaway error cannot leave a long trail after a hard edge.
                    val v = buf[0][i].coerceIn(-128f, 383f)
                    val li = nearestLuma(p, v)
                    chosen = p.byLuma[li]
                    err[0] = v - p.lumas[li]
                } else {
                    val r = buf[0][i].coerceIn(-128f, 383f)
                    val g = buf[1][i].coerceIn(-128f, 383f)
                    val b = buf[2][i].coerceIn(-128f, 383f)
                    chosen = nearestRgb(p, r, g, b)
                    err[0] = r - p.r[chosen]
                    err[1] = g - p.g[chosen]
                    err[2] = b - p.b[chosen]
                }
                dst[i] = p.colors[chosen]
                var t = 0
                while (t < taps.size) {
                    val nx = x + if (ltr) taps[t] else -taps[t]
                    val ny = y + taps[t + 1]
                    if (nx in 0 until w && ny < h) {
                        val f = taps[t + 2] / k.divisor
                        val j = ny * w + nx
                        for (ch in 0 until channels) buf[ch][j] += err[ch] * f
                    }
                    t += 3
                }
            }
        }
    }
}
