package com.chickengamer.circuitbender.core

import java.time.LocalDateTime
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class V4Test {
    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    private fun grey(v: Int) = rgb(v, v, v)

    private val osd = OsdState(
        recording = true, blinkOn = true, tapeSpeed = "SP", battery = 3,
        counterSeconds = 61, dateTime = LocalDateTime.of(1997, 7, 4, 13, 2, 0),
    )

    @Test
    fun `quality sets samples per line and keeps detail`() {
        assertEquals(listOf(160, 240, 320, 480, 640), Quality.entries.map { it.samples })
        for (q in Quality.entries) {
            val s = PipelineSettings(quality = q)
            assertEquals(q.samples, s.width, "$q width")
            assertEquals(480, s.height, "$q lines")
            assertEquals(4 to 3, s.displayWidth to s.displayHeight)
            val up = PipelineSettings(quality = q, portrait = true)
            assertEquals(q.samples * 3 / 4, up.width, "$q upright width")
            assertEquals(640, up.height)
            assertEquals(3 to 4, up.displayWidth to up.displayHeight)
        }
        // Fine stripes in the camera image (2 px on, 2 px off at 1280 wide):
        // the 640-sample camera resolves them, the 240-sample one averages them
        // away - through the real sampler and tape chain.
        val cw = 1280
        val ch = 960
        val y = ByteArray(cw * ch) { (if ((it % cw) / 2 % 2 == 0) 40 else 200).toByte() }
        val uv = ByteArray(cw * ch / 2) { 128.toByte() }
        val planes = YuvPlanes(cw, ch, y, cw, uv, 0, uv, 1, cw, 2)
        fun stripeContrast(q: Quality): Double {
            val s = PipelineSettings(quality = q, vhs = VhsSettings().noiseless())
            val w = s.width
            val src = CameraSampler.sample(planes, 0, w, s.height, s.displayWidth, s.displayHeight)
            val out = Pipeline(1).process(src, s, null, 0)
            val row = out.copyOfRange(240 * w + w / 4, 240 * w + 3 * w / 4).map { luma(it).toDouble() }
            return row.max() - row.min()
        }
        val sharp = stripeContrast(Quality.SHARP)
        val low = stripeContrast(Quality.VHS_EP)
        assertTrue(sharp > low * 1.5, "640 samples ($sharp) should keep the stripes far better than 240 ($low)")
    }

    @Test
    fun `dither goes bigger with more palettes and brightness`() {
        val steps = (0..400).map { it / 400f }
        val sizes = steps.map(Knobs::pixel).toSet()
        assertTrue(sizes.any { it.width == 640 && it.height == 480 }, "640x480 reachable")
        assertTrue(sizes.any { it.width == 480 && it.height == 360 }, "480x360 reachable")
        val palettes = steps.map(Knobs::palette).distinct()
        assertTrue(palettes.size >= 18, "only ${palettes.size} colour choices")
        assertTrue(palettes.any { it.name.contains("C64") && it.colors.size == 16 }, "C64 palette")
        assertTrue(palettes.any { it.name.contains("Spectrum") && it.colors.size == 15 }, "ZX Spectrum palette")

        val base = PipelineSettings(ditherEnabled = true, vhsEnabled = false, palette = Palettes.natural(3))
        val src = IntArray(base.width * base.height) { grey((it % base.width) * 255 / base.width) }
        val dark = Pipeline(1).process(src, base.copy(ditherBrightness = Knobs.brightness(0f)), null, 0)
        val light = Pipeline(1).process(src, base.copy(ditherBrightness = Knobs.brightness(1f)), null, 0)
        val diff = dark.indices.sumOf { abs(luma(dark[it]) - luma(light[it])).toDouble() } / dark.size
        assertTrue(diff > 20, "brightness knob must change the dither ($diff)")
        assertTrue(Knobs.brightness(0.5f) == 0f, "middle of the knob is unchanged")
    }

    @Test
    fun `overlay can be kept in photos`() {
        val s = PipelineSettings(bendEnabled = true, bend = Knobs.bend(noise = 0.4f))
        val src = IntArray(s.width * s.height) { rgb(it % 256, (it / s.width) % 256, 90) }

        val keep = Pipeline(4)
        val shown = keep.process(src, s, osd, 3, clean = true, keepOverlay = true)
        assertContentEquals(shown, keep.capture(1).pixels, "kept overlay: photo is the viewfinder picture")

        val drop = Pipeline(4)
        val shown2 = drop.process(src, s, osd, 3, clean = true, keepOverlay = false)
        val bare = Pipeline(4).process(src, s, null, 3)
        assertContentEquals(bare, drop.capture(1).pixels, "dropped overlay: photo has none")
        assertFalse(shown2.contentEquals(bare), "the viewfinder did show the overlay")
    }
}
