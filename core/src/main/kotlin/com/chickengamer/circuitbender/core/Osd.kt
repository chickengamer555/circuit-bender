package com.chickengamer.circuitbender.core

import java.time.LocalDateTime

/** What the camcorder viewfinder is showing this frame. */
data class OsdState(
    val recording: Boolean,
    val blinkOn: Boolean,
    val tapeSpeed: String,
    /** 0..3 bars. */
    val battery: Int,
    val counterSeconds: Long,
    val dateTime: LocalDateTime,
    val showDate: Boolean = true,
)

/** Date, time and tape-counter text in the 90s US camcorder style (docs/RESEARCH.md 2.4). */
object Stamp {
    private val MONTHS = arrayOf("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC")

    private fun two(n: Int) = n.toString().padStart(2, '0')

    /** "OCT.12 2026", "JAN. 1 1998". */
    fun date(dt: LocalDateTime): String =
        "${MONTHS[dt.monthValue - 1]}.${dt.dayOfMonth.toString().padStart(2, ' ')} ${dt.year}"

    /** "10:48:03 PM", " 9:05:07 AM", "12:00:00 AM" at midnight. */
    fun time(dt: LocalDateTime): String {
        val h12 = (dt.hour % 12).let { if (it == 0) 12 else it }
        val ampm = if (dt.hour < 12) "AM" else "PM"
        return "${h12.toString().padStart(2, ' ')}:${two(dt.minute)}:${two(dt.second)} $ampm"
    }

    /** Tape counter "H:MM:SS". */
    fun counter(seconds: Long): String {
        val s = maxOf(0L, seconds)
        return "${s / 3600}:${two(((s / 60) % 60).toInt())}:${two((s % 60).toInt())}"
    }
}

/**
 * 5x7 glyphs from the Hitachi HD44780 character-generator ROM (A00), the same
 * family of blocky CG fonts that camcorder OSD chips used. Each row is 5 bits,
 * bit 4 leftmost.
 */
object Font5x7 {
    const val WIDTH = 5
    const val HEIGHT = 7

    private val GLYPHS: Map<Char, IntArray> = mapOf(
        ' ' to intArrayOf(0, 0, 0, 0, 0, 0, 0),
        '0' to intArrayOf(0x0E, 0x11, 0x13, 0x15, 0x19, 0x11, 0x0E),
        '1' to intArrayOf(0x04, 0x0C, 0x04, 0x04, 0x04, 0x04, 0x0E),
        '2' to intArrayOf(0x0E, 0x11, 0x01, 0x02, 0x04, 0x08, 0x1F),
        '3' to intArrayOf(0x1F, 0x02, 0x04, 0x02, 0x01, 0x11, 0x0E),
        '4' to intArrayOf(0x02, 0x06, 0x0A, 0x12, 0x1F, 0x02, 0x02),
        '5' to intArrayOf(0x1F, 0x10, 0x1E, 0x01, 0x01, 0x11, 0x0E),
        '6' to intArrayOf(0x06, 0x08, 0x10, 0x1E, 0x11, 0x11, 0x0E),
        '7' to intArrayOf(0x1F, 0x01, 0x02, 0x04, 0x08, 0x08, 0x08),
        '8' to intArrayOf(0x0E, 0x11, 0x11, 0x0E, 0x11, 0x11, 0x0E),
        '9' to intArrayOf(0x0E, 0x11, 0x11, 0x0F, 0x01, 0x02, 0x0C),
        'A' to intArrayOf(0x0E, 0x11, 0x11, 0x11, 0x1F, 0x11, 0x11),
        'B' to intArrayOf(0x1E, 0x11, 0x11, 0x1E, 0x11, 0x11, 0x1E),
        'C' to intArrayOf(0x0E, 0x11, 0x10, 0x10, 0x10, 0x11, 0x0E),
        'D' to intArrayOf(0x1C, 0x12, 0x11, 0x11, 0x11, 0x12, 0x1C),
        'E' to intArrayOf(0x1F, 0x10, 0x10, 0x1E, 0x10, 0x10, 0x1F),
        'F' to intArrayOf(0x1F, 0x10, 0x10, 0x1E, 0x10, 0x10, 0x10),
        'G' to intArrayOf(0x0E, 0x11, 0x10, 0x17, 0x11, 0x11, 0x0F),
        'H' to intArrayOf(0x11, 0x11, 0x11, 0x1F, 0x11, 0x11, 0x11),
        'I' to intArrayOf(0x0E, 0x04, 0x04, 0x04, 0x04, 0x04, 0x0E),
        'J' to intArrayOf(0x07, 0x02, 0x02, 0x02, 0x02, 0x12, 0x0C),
        'K' to intArrayOf(0x11, 0x12, 0x14, 0x18, 0x14, 0x12, 0x11),
        'L' to intArrayOf(0x10, 0x10, 0x10, 0x10, 0x10, 0x10, 0x1F),
        'M' to intArrayOf(0x11, 0x1B, 0x15, 0x15, 0x11, 0x11, 0x11),
        'N' to intArrayOf(0x11, 0x11, 0x19, 0x15, 0x13, 0x11, 0x11),
        'O' to intArrayOf(0x0E, 0x11, 0x11, 0x11, 0x11, 0x11, 0x0E),
        'P' to intArrayOf(0x1E, 0x11, 0x11, 0x1E, 0x10, 0x10, 0x10),
        'Q' to intArrayOf(0x0E, 0x11, 0x11, 0x11, 0x15, 0x12, 0x0D),
        'R' to intArrayOf(0x1E, 0x11, 0x11, 0x1E, 0x14, 0x12, 0x11),
        'S' to intArrayOf(0x0F, 0x10, 0x10, 0x0E, 0x01, 0x01, 0x1E),
        'T' to intArrayOf(0x1F, 0x04, 0x04, 0x04, 0x04, 0x04, 0x04),
        'U' to intArrayOf(0x11, 0x11, 0x11, 0x11, 0x11, 0x11, 0x0E),
        'V' to intArrayOf(0x11, 0x11, 0x11, 0x11, 0x11, 0x0A, 0x04),
        'W' to intArrayOf(0x11, 0x11, 0x11, 0x15, 0x15, 0x15, 0x0A),
        'X' to intArrayOf(0x11, 0x11, 0x0A, 0x04, 0x0A, 0x11, 0x11),
        'Y' to intArrayOf(0x11, 0x11, 0x11, 0x0A, 0x04, 0x04, 0x04),
        'Z' to intArrayOf(0x1F, 0x01, 0x02, 0x04, 0x08, 0x10, 0x1F),
        ':' to intArrayOf(0x00, 0x0C, 0x0C, 0x00, 0x0C, 0x0C, 0x00),
        '.' to intArrayOf(0x00, 0x00, 0x00, 0x00, 0x00, 0x0C, 0x0C),
        '-' to intArrayOf(0x00, 0x00, 0x00, 0x1F, 0x00, 0x00, 0x00),
        '>' to intArrayOf(0x08, 0x0C, 0x0E, 0x0F, 0x0E, 0x0C, 0x08),
    )

    fun glyph(c: Char): IntArray? = GLYPHS[c.uppercaseChar()]
}

/** A screen rectangle, [left, right) x [top, bottom). */
data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int)

object Osd {
    /**
     * Where the date (first) and time (second) go: date bottom-right, time
     * bottom-left, or, when the frame is too narrow for both on one line,
     * the time right-aligned on the line above the date.
     */
    fun stampBoxes(width: Int, height: Int, state: OsdState): List<Box> {
        val s = scaleFor(height)
        val mx = width * 8 / 100
        val my = height * 8 / 100
        val lineH = Font5x7.HEIGHT * s
        val dw = textWidth(Stamp.date(state.dateTime), s)
        val tw = textWidth(Stamp.time(state.dateTime), s)
        val y = height - my - lineH
        val dateBox = Box(width - mx - dw, y, width - mx, y + lineH)
        val gap = 4 * s
        val timeBox = if (mx + tw + gap <= dateBox.left) {
            Box(mx, y, mx + tw, y + lineH)
        } else {
            val y2 = y - lineH - 3 * s
            Box(width - mx - tw, y2, width - mx, y2 + lineH)
        }
        return listOf(dateBox, timeBox)
    }

    private const val WHITE = 0xFFFFFFFF.toInt()
    private const val BLACK = 0xFF000000.toInt()
    private const val RED = 0xFFE01010.toInt()

    /** OSD pixel scale: 2 at 240 lines, like a real CG on a 480i picture. */
    fun scaleFor(height: Int) = maxOf(1, minOf(height, 240) / 120)

    private fun plot(dst: IntArray, w: Int, h: Int, x: Int, y: Int, c: Int) {
        if (x in 0 until w && y in 0 until h) dst[y * w + x] = c
    }

    private fun fill(dst: IntArray, w: Int, h: Int, x0: Int, y0: Int, cw: Int, ch: Int, c: Int) {
        for (y in maxOf(0, y0) until minOf(h, y0 + ch)) for (x in maxOf(0, x0) until minOf(w, x0 + cw)) dst[y * w + x] = c
    }

    /** Pixel width of [text] at [scale]. */
    fun textWidth(text: String, scale: Int = 1) = maxOf(0, text.length * 6 - 1) * scale

    /** Draw [text] with a one-(scaled)-pixel [outline] ring, clipped to the frame. */
    fun drawText(dst: IntArray, width: Int, height: Int, text: String, x: Int, y: Int, fg: Int, outline: Int, scale: Int = 1) {
        if (width <= 0 || height <= 0) return
        for (pass in 0..1) {
            text.forEachIndexed { i, ch ->
                val g = Font5x7.glyph(ch) ?: return@forEachIndexed
                val gx = x + i * 6 * scale
                for (row in 0 until Font5x7.HEIGHT) for (col in 0 until Font5x7.WIDTH) {
                    if (g[row] shr (4 - col) and 1 == 0) continue
                    val px = gx + col * scale
                    val py = y + row * scale
                    if (pass == 0) {
                        fill(dst, width, height, px - scale, py - scale, scale * 3, scale * 3, outline)
                    } else {
                        fill(dst, width, height, px, py, scale, scale, fg)
                    }
                }
            }
        }
    }

    private class Ink(val fg: Int, val outline: Int, val red: Int)

    private fun ink(palette: Palette?): Ink {
        if (palette == null) return Ink(WHITE, BLACK, RED)
        val lightest = palette.colors[palette.byLuma.last()]
        val darkest = palette.colors[palette.byLuma.first()]
        // The palette colour closest to the tally-light red, skipping the
        // outline colour so the dot stays visible.
        val red = palette.colors.filter { it != darkest }.minByOrNull {
            val dr = ((it shr 16) and 0xFF) - 224
            val dg = (it shr 8) and 0xFF
            val db = it and 0xFF
            3 * dr * dr + 4 * dg * dg + 2 * db * db
        } ?: lightest
        return Ink(lightest, darkest, red)
    }

    /**
     * The date and time stamp, which real camcorders superimpose onto the
     * recording itself - so the pipeline draws it before the tape chain.
     */
    fun drawStamp(dst: IntArray, width: Int, height: Int, state: OsdState, palette: Palette? = null) {
        if (!state.showDate || width <= 0 || height <= 0) return
        val s = scaleFor(height)
        val ink = ink(palette)
        val (dateBox, timeBox) = stampBoxes(width, height, state)
        drawText(dst, width, height, Stamp.date(state.dateTime), dateBox.left, dateBox.top, ink.fg, ink.outline, s)
        drawText(dst, width, height, Stamp.time(state.dateTime), timeBox.left, timeBox.top, ink.fg, ink.outline, s)
    }

    /** Viewfinder-only indicators: tally dot and REC/STBY, tape speed, battery, counter. */
    fun drawViewfinder(dst: IntArray, width: Int, height: Int, state: OsdState, palette: Palette? = null) {
        if (width <= 0 || height <= 0) return
        val s = scaleFor(height)
        val ink = ink(palette)
        val mx = width * 8 / 100
        val my = height * 8 / 100

        // Top-left: blinking tally dot, then REC or STBY.
        val r = 3 * s
        if (state.recording && state.blinkOn) {
            for (dy in -r..r) for (dx in -r..r) {
                val d = dx * dx + dy * dy
                if (d <= (r + 1) * (r + 1)) plot(dst, width, height, mx + r + dx, my + r + dy, if (d <= r * r) ink.red else ink.outline)
            }
        }
        val label = if (state.recording) "REC" else "STBY"
        drawText(dst, width, height, label, mx + 2 * r + 4 * s, my, ink.fg, ink.outline, s)

        // Top-right: battery, then tape speed and counter.
        val bw = 14 * s
        val bh = 7 * s
        val bx = width - mx - bw - 2 * s
        fill(dst, width, height, bx - s, my - s, bw + 2 * s, bh + 2 * s, ink.outline)
        fill(dst, width, height, bx, my, bw, bh, ink.fg)
        fill(dst, width, height, bx + s, my + s, bw - 2 * s, bh - 2 * s, ink.outline)
        fill(dst, width, height, bx + bw, my + 2 * s, 2 * s, bh - 4 * s, ink.fg)
        val bars = state.battery.coerceIn(0, 3)
        val barW = (bw - 2 * s - 4 * s) / 3
        for (i in 0 until bars) fill(dst, width, height, bx + 2 * s + i * (barW + s), my + 2 * s, barW, bh - 4 * s, ink.fg)

        val line2 = "${state.tapeSpeed} ${Stamp.counter(state.counterSeconds)}"
        drawText(dst, width, height, line2, width - mx - textWidth(line2, s), my + bh + 4 * s, ink.fg, ink.outline, s)
    }
}
