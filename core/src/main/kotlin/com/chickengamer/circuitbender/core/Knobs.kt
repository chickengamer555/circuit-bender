package com.chickengamer.circuitbender.core

/**
 * The front panel: a few knobs per effect, each 0..1, mapped onto the full
 * engine.
 */
object Knobs {
    /**
     * NOISE: data lines shorted through pots, sparkle growing into
     * posterisation. TEAR: the pixel-clock pot past its knee, then a lost
     * HSYNC slant. COLOUR: analog gain, a Bayer phase slip, crossed lines, a
     * byte slip. MEMORY: an intermittent stuck frame-buffer address line -
     * repeated blocks and ghosts of earlier frames. ROLL: lost VSYNC. BLOCKS:
     * JPEG bit errors.
     */
    fun bend(
        noise: Float = 0f,
        tear: Float = 0f,
        colour: Float = 0f,
        memory: Float = 0f,
        roll: Float = 0f,
        blocks: Float = 0f,
    ): BendSettings {
        val n = noise.coerceIn(0f, 1f)
        val t = tear.coerceIn(0f, 1f)
        val c = colour.coerceIn(0f, 1f)
        val m = memory.coerceIn(0f, 1f)
        val lines = MutableList(8) { DataLine() }
        if (n > 0f) {
            lines[1] = DataLine(LineMode.HIGH, pot = 1f - n)
            lines[3] = DataLine(LineMode.LOW, pot = 1f - n * 0.8f)
            lines[6] = DataLine(LineMode.HIGH, pot = 1f - n * 0.35f)
        }
        return BendSettings(
            dataLines = lines,
            pixelClockPot = if (t > 0f) BendSettings.PCLK_KNEE + 0.5f * t else 0f,
            strideOffset = if (t > 0.5f) ((t - 0.5f) / 0.5f * 10f).toInt() else 0,
            adcGain = 1f + c * 1.2f,
            bayerPhase = if (c >= 0.33f) BayerPhase.SHIFT_X else BayerPhase.NONE,
            crossA = if (c >= 0.66f) 7 else 0,
            crossB = if (c >= 0.66f) 5 else 0,
            bytePhaseSlip = c >= 0.9f,
            addressMode = if (m > 0f) LineMode.LOW else LineMode.OFF,
            addressBit = -(1 + (m * 6f).toInt()),
            addressDuty = 0.3f + 0.6f * m,
            vsyncRoll = roll.coerceIn(0f, 1f) * 8f,
            jpegErrors = blocks.coerceIn(0f, 1f),
        )
    }

    /** WEAR (noise, dropouts, wobble, tracking), BLEED (colour smear), SOFT (blur), GLOW (bloom, lifted blacks). */
    fun vhs(wear: Float, bleed: Float, soft: Float, glow: Float): VhsSettings {
        val w = wear.coerceIn(0f, 1f)
        val b = bleed.coerceIn(0f, 1f)
        val s = soft.coerceIn(0f, 1f)
        val g = glow.coerceIn(0f, 1f)
        return VhsSettings(
            lumaNoise = 0.002f + 0.02f * w,
            chromaNoise = 0.02f + 0.2f * w,
            snow = 0.000005f + 0.00025f * w,
            dropouts = 0.03f + 0.9f * w,
            jitterPx = 0.2f + 1.6f * w,
            headSwitchLines = 3 + (5 * w).toInt(),
            tracking = if (w > 0.7f) (w - 0.7f) / 0.3f * 0.6f else 0f,
            chromaDivisor = 6 + (8 * b).toInt(),
            chromaDelayPx = 1f + 2.5f * b,
            saturation = 0.85f + 0.25f * b,
            lumaSmear = 0.3f + 0.7f * s,
            sharpen = 0.7f - 0.5f * s,
            bloom = 1.6f * g,
            blackLift = 6f + 24f * g,
        )
    }

    private val STYLES = DitherAlgorithm.entries
    private val PALETTES = listOf(
        Palettes.natural(2), Palettes.natural(3), Palettes.natural(4), Palettes.natural(6), Palettes.natural(8),
        Palettes.ONE_BIT, Palettes.GAME_BOY, Palettes.GAME_BOY_BGB, Palettes.GAME_BOY_POCKET,
        Palettes.CGA_1_HIGH, Palettes.CGA_0_HIGH, Palettes.CGA_5_HIGH, Palettes.EGA_16, Palettes.PICO_8,
    )
    private val PIXELS = Resolution.entries

    private fun <T> pick(options: List<T>, k: Float): T =
        options[(k.coerceIn(0f, 1f) * (options.size - 1) + 0.5f).toInt()]

    fun ditherStyle(k: Float): DitherAlgorithm = pick(STYLES, k)
    fun palette(k: Float): Palette = pick(PALETTES, k)
    fun pixel(k: Float): Resolution = pick(PIXELS, k)

    /** 0 -> half contrast, 0.5 -> unchanged, 1 -> double. */
    fun contrast(k: Float): Float = 0.5f * Math.pow(4.0, k.coerceIn(0f, 1f).toDouble()).toFloat()
}
