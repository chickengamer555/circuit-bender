package com.chickengamer.circuitbender.core

import kotlin.random.Random

/**
 * The colour bend, from what real bent cameras produce (docs/COLOUR-RESEARCH.md):
 * brightness-locked bands of opposing false colours, colour confetti in
 * texture, and at the extreme green shadows against magenta highlights - never
 * a single tint. It works on the YUV422 byte stream (Y0 U Y1 V) like the real
 * bus, in four stages that take over one after another as the knob turns:
 *
 * 1. The colour (U/V) amplifier is overdriven and wraps around, so strong
 *    colours flip to their opposite. It rises steadily with the knob, and
 *    pushing colours away from grey in both directions keeps the false
 *    colours two-sided.
 * 2. 0.05-0.85: a slow line lets bits of the brightness byte that went before
 *    leak into the colour byte - hue flips every few brightness levels, the
 *    contour "thermal map" banding.
 * 3. 0.7-1: on up to 60% of colour bytes the sign bit follows the brightness sign bit, so
 *    shadows go lime green and highlights hot magenta.
 * 4. 0.8-1: D6 and D4 bridged across the whole bus on a loose contact that
 *    makes or breaks for whole frames - posterised brightness and colour.
 *
 * A one-byte YUYV slip was tried for the top end; it filled the picture
 * with 2-pixel stripes and blocks that real bent photos do not show, so
 * the slip lives only in the separate BYTE SLIP rack switch.
 */
object ColourFault {
    private fun ramp(c: Float, from: Float, to: Float) = ((c - from) / (to - from)).coerceIn(0f, 1f)

    fun apply(bytes: ByteArray, pw: Int, h: Int, c: Float, frame: Long, rowRng: (Int, Int) -> Random) {
        val lb = pw * 2
        val gain = 1f + 2f * c
        val leak3 = ramp(c, 0.05f, 0.4f)
        val leak5 = ramp(c, 0.3f, 0.65f)
        val leak6 = ramp(c, 0.5f, 0.85f)
        val bridge = ramp(c, 0.8f, 1f)
        val leak7 = 0.6f * ramp(c, 0.7f, 1f)

        // The bridge is a loose contact: it makes or breaks for whole frames.
        val bridged = bridge > 0f && rowRng(6, 0).nextFloat() < bridge
        Parallel.rows(h) { from, to ->
            for (y in from until to) {
                val o = y * lb
                val rnd = rowRng(5, y)
                // Stages 1 and 2 on each colour byte, reading the brightness byte before it.
                var i = o + 1
                while (i < o + lb) {
                    var v = bytes[i].toInt() and 0xFF
                    if (gain > 1f) v = ((v - 128) * gain + 128f).toInt() and 0xFF
                    val prevY = bytes[i - 1].toInt() and 0xFF
                    if (leak3 > 0f && rnd.nextFloat() < leak3) v = (v and 0x10.inv()) or (((prevY shr 3) and 1) shl 4)
                    if (leak5 > 0f && rnd.nextFloat() < leak5) v = (v and 0x20.inv()) or (((prevY shr 4) and 1) shl 5)
                    if (leak6 > 0f && rnd.nextFloat() < leak6) v = (v and 0x40.inv()) or (((prevY shr 5) and 1) shl 6)
                    // The sign bit follows the brightness sign bit: shadows push both
                    // colour differences negative (green), highlights positive (magenta).
                    if (leak7 > 0f && rnd.nextFloat() < leak7) v = (v and 0x80.inv()) or (prevY and 0x80)
                    bytes[i] = v.toByte()
                    i += 2
                }
                if (bridged) {
                    for (j in o until o + lb) {
                        val b = bytes[j].toInt()
                        val on = (b and 0x40 != 0) && (b and 0x10 != 0)
                        bytes[j] = (if (on) b or 0x50 else b and 0x50.inv()).toByte()
                    }
                }
            }
        }
    }
}
