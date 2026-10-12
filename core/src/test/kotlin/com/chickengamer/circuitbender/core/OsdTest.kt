package com.chickengamer.circuitbender.core

import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OsdTest {
    private val black = 0xFF000000.toInt()
    private val white = 0xFFFFFFFF.toInt()
    private val mid = 0xFF707070.toInt()

    private fun state(dt: LocalDateTime = LocalDateTime.of(2026, 10, 12, 22, 48, 3)) = OsdState(
        recording = true,
        blinkOn = true,
        tapeSpeed = "SP",
        battery = 3,
        counterSeconds = 754,
        dateTime = dt,
    )

    @Test
    fun `stamps are formatted like a camcorder`() {
        assertEquals("OCT.12 2026", Stamp.date(LocalDateTime.of(2026, 10, 12, 22, 48, 3)))
        assertEquals("10:48:03 PM", Stamp.time(LocalDateTime.of(2026, 10, 12, 22, 48, 3)))
        assertEquals("JAN. 1 1998", Stamp.date(LocalDateTime.of(1998, 1, 1, 0, 0, 0)))
        assertEquals("12:00:00 AM", Stamp.time(LocalDateTime.of(1998, 1, 1, 0, 0, 0)))
        assertEquals("12:30:00 PM", Stamp.time(LocalDateTime.of(1998, 1, 1, 12, 30, 0)))
        assertEquals(" 9:05:07 AM", Stamp.time(LocalDateTime.of(1998, 5, 3, 9, 5, 7)))
        assertEquals("0:12:34", Stamp.counter(754))
        assertEquals("1:02:05", Stamp.counter(3725))
        assertEquals("0:00:00", Stamp.counter(0))
        assertEquals("0:00:00", Stamp.counter(-5))
    }

    @Test
    fun `text is outlined block glyphs`() {
        val used = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ:.- "
        for (c in used) {
            val g = assertNotNull(Font5x7.glyph(c), "missing glyph for '$c'")
            assertEquals(7, g.size)
            assertTrue(g.all { it in 0..0x1F }, "glyph '$c' must be 5 px wide")
            if (c != ' ') assertTrue(g.any { it != 0 }, "glyph '$c' is blank")
        }
        assertTrue(Font5x7.glyph('A')!!.toList() != Font5x7.glyph('B')!!.toList())

        val w = 20
        val h = 14
        val px = IntArray(w * h) { mid }
        Osd.drawText(px, w, h, "A", 5, 3, white, black)
        val whites = px.indices.filter { px[it] == white }
        assertTrue(whites.size >= 10, "the A is drawn")
        // Every lit pixel sits next to outline or another lit pixel, and the
        // outline actually surrounds the glyph.
        assertTrue(px.count { it == black } >= whites.size, "outline is drawn")
        for (i in whites) {
            val x = i % w
            val y = i / w
            val ring = listOf(x - 1 to y, x + 1 to y, x to y - 1, x to y + 1)
            assertTrue(ring.none { (nx, ny) -> px[ny * w + nx] == mid }, "lit pixel $x,$y touches bare background")
        }
    }

    @Test
    fun `overlay elements land in their corners`() {
        val w = 320
        val h = 240
        val px = IntArray(w * h) { mid }
        Osd.drawStamp(px, w, h, state())
        Osd.drawViewfinder(px, w, h, state())
        val red = px.indices.filter { val c = px[it]; (c shr 16 and 0xFF) > 180 && (c shr 8 and 0xFF) < 80 && (c and 0xFF) < 80 }
        assertTrue(red.isNotEmpty(), "the REC dot is red")
        assertTrue(red.all { it % w < w / 4 && it / w < h / 4 }, "red only in the top-left")
        // The date sits in the bottom-right quadrant.
        val br = (h / 2 until h).sumOf { y -> (w / 2 until w).count { x -> px[y * w + x] == white } }
        assertTrue(br > 40, "date stamp pixels bottom-right ($br)")
        val topLeftText = (0 until h / 4).sumOf { y -> (0 until w / 4).count { x -> px[y * w + x] == white } }
        assertTrue(topLeftText > 10, "REC text top-left ($topLeftText)")

        // The date is the bottom-right text and the time the bottom-left one,
        // not the other way round: compare against each string drawn alone.
        val st = state()
        val stamp = IntArray(w * h) { mid }
        Osd.drawStamp(stamp, w, h, st)
        val sc = Osd.scaleFor(h)
        val y0 = h - h * 8 / 100 - Font5x7.HEIGHT * sc
        val dateOnly = IntArray(w * h) { mid }
        val date = Stamp.date(st.dateTime)
        Osd.drawText(dateOnly, w, h, date, w - w * 8 / 100 - Osd.textWidth(date, sc), y0, white, black, sc)
        val timeOnly = IntArray(w * h) { mid }
        Osd.drawText(timeOnly, w, h, Stamp.time(st.dateTime), w * 8 / 100, y0, white, black, sc)
        for (y in h / 2 until h) for (x in 0 until w) {
            val expected = if (x >= w / 2) dateOnly[y * w + x] else timeOnly[y * w + x]
            assertEquals(expected, stamp[y * w + x], "stamp pixel $x,$y")
        }
    }

    @Test
    fun `overlay can be restricted to palette colours`() {
        val w = 320
        val h = 240
        for (palette in listOf(Palettes.ONE_BIT, Palettes.GAME_BOY, Palettes.PICO_8)) {
            val px = IntArray(w * h) { palette.colors[0] }
            Osd.drawStamp(px, w, h, state(), palette)
            Osd.drawViewfinder(px, w, h, state(), palette)
            val allowed = palette.colors.toSet()
            assertTrue(px.all { it in allowed }, "${palette.name} overlay stays in palette")
            assertTrue(px.any { it != palette.colors[0] }, "${palette.name} overlay is still drawn")
        }
    }

    @Test
    fun `overlay clips in tiny frames`() {
        for ((w, h) in listOf(1 to 1, 10 to 4, 0 to 0)) {
            val px = IntArray(w * h) { mid }
            Osd.drawStamp(px, w, h, state())
            Osd.drawViewfinder(px, w, h, state())
            Osd.drawText(px, w, h, "TAPE END", -3, -2, white, black)
            assertEquals(w * h, px.size)
        }
        // Clipping keeps the visible part of the text rather than skipping it.
        val px = IntArray(10 * 4) { mid }
        Osd.drawText(px, 10, 4, "TAPE END", -3, -2, white, black)
        assertTrue(px.any { it == white } && px.any { it == black }, "partly visible text is drawn")
    }
}
