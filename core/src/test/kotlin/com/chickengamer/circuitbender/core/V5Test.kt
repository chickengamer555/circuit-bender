package com.chickengamer.circuitbender.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class V5Test {
    @Test
    fun `frames come out upright however the phone is held`() {
        // Back camera: (sensor + device) mod 360. Front: (sensor - device) mod 360.
        for (device in listOf(0, 90, 180, 270)) {
            assertEquals((90 + device) % 360, CameraSampler.sampleRotation(90, device, front = false), "back, device $device")
            assertEquals((270 - device + 360) % 360, CameraSampler.sampleRotation(270, device, front = true), "front, device $device")
        }
        assertEquals(0, CameraSampler.sampleRotation(270, 90, front = false))
        assertEquals(180, CameraSampler.sampleRotation(90, 270, front = true))
    }

    @Test
    fun `tap maps to the same spot on the sensor`() {
        val sw = 160
        val sh = 120
        for (rot in listOf(0, 90, 180, 270)) for ((aw, ah) in listOf(4 to 3, 3 to 4)) {
            for ((u, v) in listOf(0.2f to 0.3f, 0.8f to 0.7f, 0.5f to 0.15f)) {
                val (sx, sy) = CameraSampler.sensorPoint(u, v, rot, sw, sh, aw, ah)
                assertTrue(sx in 0f..1f && sy in 0f..1f, "rot $rot $aw:$ah: point ($sx, $sy) off the sensor")
                // A bright block on the sensor where the tap says...
                val y = ByteArray(sw * sh) { 20 }
                val px = (sx * sw).toInt()
                val py = (sy * sh).toInt()
                for (yy in py - 3..py + 3) for (xx in px - 3..px + 3) {
                    if (xx in 0 until sw && yy in 0 until sh) y[yy * sw + xx] = 230.toByte()
                }
                val uv = ByteArray(sw * sh / 2) { 128.toByte() }
                val planes = YuvPlanes(sw, sh, y, sw, uv, 0, uv, 1, sw, 2)
                val ow = aw * 20
                val oh = ah * 20
                val out = CameraSampler.sample(planes, rot, ow, oh, aw, ah)
                // ...must be where the tap was in the picture.
                val at = out[(v * oh).toInt().coerceAtMost(oh - 1) * ow + (u * ow).toInt().coerceAtMost(ow - 1)]
                assertTrue((at and 0xFF) > 150, "rot $rot $aw:$ah tap ($u, $v): picture is dark there")
            }
        }
    }
}
