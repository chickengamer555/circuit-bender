package com.chickengamer.circuitbender

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/** The round shutter: white for photos, red for video, a red square while recording. */
class ShutterView(context: Context) : View(context) {
    var video = false
        set(value) {
            field = value
            invalidate()
        }
    var recording = false
        set(value) {
            field = value
            invalidate()
        }

    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val box = RectF()

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(width, height) / 2f
        ring.strokeWidth = r * 0.09f
        canvas.drawCircle(cx, cy, r - ring.strokeWidth, ring)
        fill.color = if (video) 0xFFFF3B30.toInt() else Color.WHITE
        if (recording) {
            val s = r * 0.42f
            box.set(cx - s, cy - s, cx + s, cy + s)
            canvas.drawRoundRect(box, s * 0.25f, s * 0.25f, fill)
        } else {
            canvas.drawCircle(cx, cy, r * 0.74f, fill)
        }
    }

    override fun setPressed(pressed: Boolean) {
        super.setPressed(pressed)
        alpha = if (pressed) 0.6f else 1f
    }
}
