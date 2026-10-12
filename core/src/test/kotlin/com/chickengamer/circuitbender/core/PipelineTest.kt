package com.chickengamer.circuitbender.core

import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PipelineTest {
    private fun r(c: Int) = (c shr 16) and 0xFF
    private fun g(c: Int) = (c shr 8) and 0xFF
    private fun b(c: Int) = c and 0xFF

    private val osd = OsdState(
        recording = true, blinkOn = true, tapeSpeed = "SP", battery = 2,
        counterSeconds = 61, dateTime = LocalDateTime.of(1997, 7, 4, 13, 2, 0),
    )

    /**
     * A camera frame w x h: dark everywhere, a bright red block in the
     * top-left (in sensor orientation), with padded rows and interleaved
     * chroma (U and V share one buffer, pixelStride 2, as on most phones).
     */
    private fun cameraFrame(w: Int, h: Int, block: Int): YuvPlanes {
        val yStride = w + 16
        val y = ByteArray(yStride * h) { 20 }
        val cw = w / 2
        val ch = h / 2
        val uvStride = w + 8
        val uv = ByteArray(uvStride * ch + 1) { 128.toByte() }
        for (yy in 0 until block) for (xx in 0 until block) y[yy * yStride + xx] = 110.toByte()
        // Red: Y=110 with V high, U low (BT.601).
        for (yy in 0 until block / 2) for (xx in 0 until block / 2) {
            uv[yy * uvStride + xx * 2] = 90.toByte() // U
            uv[yy * uvStride + xx * 2 + 1] = 230.toByte() // V
        }
        // V buffer starts one byte after U, NV21-style views of one array.
        return YuvPlanes(
            width = w, height = h,
            y = y, yRowStride = yStride,
            u = uv, uOffset = 0,
            v = uv, vOffset = 1,
            uvRowStride = uvStride, uvPixelStride = 2,
        )
    }

    private fun isRed(c: Int) = r(c) > 150 && g(c) < 90 && b(c) < 90

    @Test
    fun `camera frames are downsampled upright`() {
        val (ow, oh) = 32 to 24
        // Red block top-left in sensor orientation; the sensor frame is shaped
        // so that the upright image is 4:3 and nothing is cropped.
        val frame = cameraFrame(128, 96, 32)
        val tall = cameraFrame(96, 128, 32)
        val corners = mapOf(
            0 to (2 to 2),
            90 to (ow - 3 to 2),
            180 to (ow - 3 to oh - 3),
            270 to (2 to oh - 3),
        )
        for ((rot, at) in corners) {
            val out = CameraSampler.sample(if (rot % 180 == 0) frame else tall, rot, ow, oh)
            assertEquals(ow * oh, out.size)
            val (x, y) = at
            assertTrue(isRed(out[y * ow + x]), "rotation $rot: red expected at $x,$y, got ${Integer.toHexString(out[y * ow + x])}")
            val opposite = out[(oh - 1 - y) * ow + (ow - 1 - x)]
            assertFalse(isRed(opposite), "rotation $rot: opposite corner must not be red")
        }

        // 16:9 source is centre-cropped to 4:3: a block in the far left
        // columns falls outside the crop and must not appear.
        val wide = cameraFrame(160, 90, 18)
        val cropped = CameraSampler.sample(wide, 0, ow, oh)
        assertTrue(cropped.none(::isRed), "16:9 left edge is cropped away")

        // Game Boy Camera size.
        assertEquals(128 * 112, CameraSampler.sample(frame, 0, 128, 112).size)
    }

    @Test
    fun `photo is the viewfinder frame scaled up`() {
        val pipeline = Pipeline(seed = 3)
        val (w, h) = Resolution.CAMCORDER.width to Resolution.CAMCORDER.height
        val src = IntArray(w * h) { (0xFF shl 24) or (it * 2654435761L.toInt() ushr 8) }
        val settings = PipelineSettings(bendEnabled = true, bend = BendSettings(strideOffset = 3))
        val shown = pipeline.process(src, settings, osd, frame = 12)
        val k = 3
        val photo = pipeline.capture(k)
        assertEquals(w * k, photo.width)
        assertEquals(h * k, photo.height)
        for (y in 0 until h * k) for (x in 0 until w * k) {
            if (photo.pixels[y * w * k + x] != shown[(y / k) * w + x / k]) {
                throw AssertionError("photo pixel $x,$y differs from viewfinder pixel ${x / k},${y / k}")
            }
        }
    }

    @Test
    fun `filters toggle independently`() {
        val (w, h) = Resolution.CAMCORDER.width to Resolution.CAMCORDER.height
        val src = IntArray(w * h) { (0xFF shl 24) or ((it % w) shl 16) or (((it / w) % 256) shl 8) or (it % 97) }
        val base = PipelineSettings()

        val dithered = Pipeline(1).process(
            src, base.copy(ditherEnabled = true, palette = Palettes.GAME_BOY, algorithm = DitherAlgorithm.BAYER_4), osd, 0,
        )
        val allowed = Palettes.GAME_BOY.colors.toSet()
        assertTrue(dithered.all { it in allowed }, "dithered photo, overlay included, uses only palette colours")

        val plain = Pipeline(1).process(src, base, osd, 0)
        val bent = Pipeline(1).process(src, base.copy(bendEnabled = true, bend = BendSettings(crosstalkBit = 7)), osd, 0)
        assertFalse(plain.contentEquals(bent), "bend changes the picture")
        val bendOff = Pipeline(1).process(src, base.copy(bendEnabled = false, bend = BendSettings(crosstalkBit = 7)), osd, 0)
        assertTrue(plain.contentEquals(bendOff), "bend switched off ignores its rack settings")
    }

    @Test
    fun `full chain is fast enough for live preview`() {
        val (w, h) = Resolution.CAMCORDER.width to Resolution.CAMCORDER.height
        val src = IntArray(w * h) { (0xFF shl 24) or (it * 2654435761L.toInt() ushr 8) }
        val settings = PipelineSettings(
            bendEnabled = true,
            bend = BendSettings(
                dataLines = List(8) { if (it == 6) DataLine(LineMode.HIGH, 0.4f) else DataLine() },
                pixelClockPot = 0.8f, strideOffset = 2, vsyncRoll = 2f, addressMode = LineMode.LOW,
                bayerPhase = BayerPhase.SHIFT_X, jpegErrors = 0.5f, brownout = 0.2f,
            ),
            ditherEnabled = true,
            algorithm = DitherAlgorithm.FLOYD_STEINBERG,
            palette = Palettes.PICO_8,
        )
        val pipeline = Pipeline(1)
        // A live viewfinder runs thousands of frames: measure the JIT-compiled
        // steady state, not compilation.
        repeat(100) { pipeline.process(src, settings, osd, it.toLong()) }
        val times = (0 until 15).map {
            val t0 = System.nanoTime()
            pipeline.process(src, settings, osd, 1000L + it)
            (System.nanoTime() - t0) / 1e6
        }.sorted()
        val median = times[times.size / 2]
        assertTrue(median < 20.0, "median frame time $median ms")
    }
}
