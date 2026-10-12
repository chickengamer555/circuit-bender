package com.chickengamer.circuitbender.core

import java.time.LocalDateTime
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SimpleTest {
    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    private fun chart(w: Int, h: Int) = IntArray(w * h) {
        val x = it % w
        val y = it / w
        rgb((x * 255) / (w - 1), (y * 255) / (h - 1), ((x + y) * 5) and 0xFF)
    }

    private val osd = OsdState(
        recording = true, blinkOn = true, tapeSpeed = "SP", battery = 3,
        counterSeconds = 754, dateTime = LocalDateTime.of(2026, 10, 12, 22, 48, 3),
    )

    private fun meanDiff(a: IntArray, b: IntArray): Double = a.indices.sumOf { i ->
        val x = a[i]
        val y = b[i]
        (abs(((x shr 16) and 0xFF) - ((y shr 16) and 0xFF)) +
            abs(((x shr 8) and 0xFF) - ((y shr 8) and 0xFF)) +
            abs((x and 0xFF) - (y and 0xFF))).toDouble()
    } / a.size

    @Test
    fun `bend knobs at zero leave the picture alone`() {
        assertTrue(Knobs.bend(0f, 0f, 0f).isNeutral)
    }

    @Test
    fun `each bend knob gets stronger as it turns`() {
        val w = 160
        val h = 120
        val src = chart(w, h)
        val knobs = mapOf<String, (Float) -> BendSettings>(
            "NOISE" to { k -> Knobs.bend(k, 0f, 0f) },
            "TEAR" to { k -> Knobs.bend(0f, k, 0f) },
            "COLOUR" to { k -> Knobs.bend(0f, 0f, k) },
        )
        for ((name, at) in knobs) {
            val low = meanDiff(src, BendEngine(3).process(src, w, h, at(0.3f), 5))
            val high = meanDiff(src, BendEngine(3).process(src, w, h, at(1f), 5))
            assertTrue(low > 0.5, "$name at 0.3 should visibly change the picture ($low)")
            assertTrue(high > low * 1.3, "$name at 1.0 ($high) should be clearly stronger than at 0.3 ($low)")
        }
    }

    @Test
    fun `dither knobs pick basic styles palettes and sizes`() {
        assertEquals(DitherAlgorithm.BAYER_4, Knobs.ditherStyle(0f))
        assertEquals(DitherAlgorithm.FLOYD_STEINBERG, Knobs.ditherStyle(0.35f))
        assertEquals(DitherAlgorithm.ATKINSON, Knobs.ditherStyle(0.65f))
        assertEquals(DitherAlgorithm.GAME_BOY_CAMERA, Knobs.ditherStyle(1f))
        assertEquals(Palettes.ONE_BIT, Knobs.palette(0f))
        assertEquals(Palettes.GAME_BOY, Knobs.palette(0.35f))
        assertEquals(Palettes.CGA_1_HIGH, Knobs.palette(0.65f))
        assertEquals(Palettes.PICO_8, Knobs.palette(1f))
        assertEquals(Resolution.CAMCORDER, Knobs.pixel(0f))
        assertEquals(Resolution.LOFI, Knobs.pixel(0.5f))
        assertEquals(Resolution.GAME_BOY, Knobs.pixel(1f))

        for (style in listOf(0f, 0.35f, 0.65f, 1f)) for (pal in listOf(0f, 0.35f, 0.65f, 1f)) for (px in listOf(0f, 1f)) {
            val s = PipelineSettings(
                resolution = Knobs.pixel(px), ditherEnabled = true,
                algorithm = Knobs.ditherStyle(style), palette = Knobs.palette(pal),
            )
            val out = Pipeline(1).process(chart(s.width, s.height), s, osd, 0)
            val allowed = s.palette.colors.toSet()
            assertTrue(out.all { it in allowed }, "style $style palette $pal pixel $px leaves the palette")
        }
    }

    @Test
    fun `photos have no overlay`() {
        val s = PipelineSettings(bendEnabled = true, bend = Knobs.bend(0.4f, 0.2f, 0f))
        val src = chart(s.width, s.height)
        val withOverlay = Pipeline(9)
        withOverlay.process(src, s, osd, 7, clean = true)
        val photo = withOverlay.capture(2)
        val bare = Pipeline(9).process(src, s, null, 7)
        assertEquals(s.width * 2, photo.width)
        assertEquals(s.height * 2, photo.height)
        for (y in 0 until photo.height) for (x in 0 until photo.width) {
            if (photo.pixels[y * photo.width + x] != bare[(y / 2) * s.width + x / 2]) {
                throw AssertionError("photo pixel $x,$y is not the overlay-free frame")
            }
        }
    }

    @Test
    fun `overlay goes through the tape filter`() {
        val s = PipelineSettings()
        val w = s.width
        val h = s.height
        val src = IntArray(w * h) { rgb(90, 90, 90) }
        // Where would the overlay land, and in what colours, if drawn crisply?
        val mask = src.copyOf()
        Osd.drawStamp(mask, w, h, osd)
        Osd.drawViewfinder(mask, w, h, osd)
        val under = mask.indices.filter { mask[it] != src[it] }
        assertTrue(under.size > 200, "the overlay covers some pixels")
        val shown = Pipeline(2).process(src, s, osd, 3)
        val crisp = under.count { shown[it] == mask[it] }
        assertTrue(crisp < under.size / 2, "overlay should be filtered, but $crisp of ${under.size} pixels are crisp")
        // ...and it is still there.
        val bare = Pipeline(2).process(src, s, null, 3)
        assertTrue(meanDiff(shown, bare) > 1.0, "overlay must still show in the viewfinder")
    }

    @Test
    fun `portrait frames are upright`() {
        val s = PipelineSettings(portrait = true)
        assertEquals(240, s.width)
        assertEquals(320, s.height)

        // A sensor frame (landscape, as phones deliver it) with a bright block
        // in its top-left; rotated 90 it must land top-right of a 3:4 picture.
        val sw = 128
        val sh = 96
        val y = ByteArray(sw * sh) { 20 }
        for (yy in 0 until 24) for (xx in 0 until 24) y[yy * sw + xx] = 230.toByte()
        val uv = ByteArray(sw * sh / 2) { 128.toByte() }
        val planes = YuvPlanes(sw, sh, y, sw, uv, 0, uv, 1, sw, 2)
        val out = CameraSampler.sample(planes, 90, 24, 32)
        fun bright(c: Int) = (c and 0xFF) > 150
        assertTrue(bright(out[1 * 24 + 22]), "marker top-right")
        assertTrue(!bright(out[1 * 24 + 1]) && !bright(out[30 * 24 + 22]), "and nowhere else")

        val p = Pipeline(1)
        p.process(chart(240, 320), s, osd, 0, clean = true)
        val photo = p.capture(4)
        assertEquals(960, photo.width)
        assertEquals(1280, photo.height)
    }

    @Test
    fun `date and time never overlap`() {
        for ((w, h) in listOf(320 to 240, 160 to 120, 128 to 112, 240 to 320)) {
            val boxes = Osd.stampBoxes(w, h, osd)
            assertEquals(2, boxes.size)
            val (a, b) = boxes
            val overlap = a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom
            assertTrue(!overlap, "${w}x$h: date $a and time $b overlap")
            for (box in boxes) {
                assertTrue(box.left >= 0 && box.top >= 0 && box.right <= w && box.bottom <= h, "${w}x$h: $box leaves the frame")
            }
        }
        // The stamp is drawn where the boxes say: nothing outside them changes.
        val w = 128
        val h = 112
        val px = IntArray(w * h) { rgb(90, 90, 90) }
        Osd.drawStamp(px, w, h, osd)
        val boxes = Osd.stampBoxes(w, h, osd)
        for (i in px.indices) {
            val x = i % w
            val y = i / w
            // One pixel of outline may sit just outside a box.
            val inside = boxes.any { x >= it.left - 1 && x < it.right + 1 && y >= it.top - 1 && y < it.bottom + 1 }
            if (!inside) assertEquals(rgb(90, 90, 90), px[i], "stamp drew outside its boxes at $x,$y")
        }
    }
}
