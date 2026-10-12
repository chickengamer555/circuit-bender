package com.chickengamer.circuitbender.core

/**
 * The simple front panel: three knobs each for bend and dither, mapped onto
 * the full engine. Every knob runs 0..1.
 */
object Knobs {
    /**
     * NOISE: data lines shorted through pots - sparkle that grows into
     * posterisation, then JPEG block errors at the top of the range.
     * TEAR: the pixel-clock pot past its knee, then a lost HSYNC slant, then
     * VSYNC roll. COLOUR: analog gain, then a Bayer phase slip, then crossed
     * data lines and finally a byte slip.
     */
    fun bend(noise: Float, tear: Float, colour: Float): BendSettings {
        val n = noise.coerceIn(0f, 1f)
        val t = tear.coerceIn(0f, 1f)
        val c = colour.coerceIn(0f, 1f)
        val lines = MutableList(8) { DataLine() }
        if (n > 0f) {
            lines[1] = DataLine(LineMode.HIGH, pot = 1f - n)
            lines[3] = DataLine(LineMode.LOW, pot = 1f - n * 0.8f)
            lines[6] = DataLine(LineMode.HIGH, pot = 1f - n * 0.35f)
        }
        return BendSettings(
            dataLines = lines,
            jpegErrors = if (n > 0.7f) (n - 0.7f) / 0.3f * 0.6f else 0f,
            pixelClockPot = if (t > 0f) BendSettings.PCLK_KNEE + 0.5f * t else 0f,
            strideOffset = if (t > 0.6f) ((t - 0.6f) / 0.4f * 8f).toInt() else 0,
            vsyncRoll = if (t > 0.85f) (t - 0.85f) / 0.15f * 4f else 0f,
            adcGain = 1f + c * 1.2f,
            bayerPhase = if (c >= 0.33f) BayerPhase.SHIFT_X else BayerPhase.NONE,
            crossA = if (c >= 0.66f) 7 else 0,
            crossB = if (c >= 0.66f) 5 else 0,
            bytePhaseSlip = c >= 0.9f,
        )
    }

    private val STYLES = listOf(
        DitherAlgorithm.BAYER_4,
        DitherAlgorithm.FLOYD_STEINBERG,
        DitherAlgorithm.ATKINSON,
        DitherAlgorithm.GAME_BOY_CAMERA,
    )
    private val PALETTES = listOf(Palettes.ONE_BIT, Palettes.GAME_BOY, Palettes.CGA_1_HIGH, Palettes.PICO_8)
    private val PIXELS = listOf(Resolution.CAMCORDER, Resolution.LOFI, Resolution.GAME_BOY)

    private fun <T> pick(options: List<T>, k: Float): T =
        options[(k.coerceIn(0f, 1f) * (options.size - 1) + 0.5f).toInt()]

    fun ditherStyle(k: Float): DitherAlgorithm = pick(STYLES, k)
    fun palette(k: Float): Palette = pick(PALETTES, k)
    fun pixel(k: Float): Resolution = pick(PIXELS, k)
}
