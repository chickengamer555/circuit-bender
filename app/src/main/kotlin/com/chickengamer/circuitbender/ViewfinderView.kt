package com.chickengamer.circuitbender

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.view.View

/**
 * Shows the processed frame letterboxed at its own aspect ratio, scaled with
 * nearest-neighbour so every low-res pixel stays a hard-edged block.
 *
 * Three bitmaps rotate: one being shown, one the render thread may still be
 * reading from the previous draw, and one free to write - so the camera
 * thread never writes into a bitmap that is on screen.
 */
class ViewfinderView(context: Context) : View(context) {
    private val lock = Any()
    private val ring = arrayOfNulls<Bitmap>(3)
    private var shown = -1
    private var next = 0
    private val paint = Paint().apply { isFilterBitmap = false }
    private val textPaint = Paint().apply {
        color = Retro.AMBER
        typeface = Typeface.MONOSPACE
        textAlign = Paint.Align.CENTER
        textSize = 18f * context.resources.displayMetrics.density
    }
    private val dst = Rect()

    @Volatile var message: String? = null
        set(value) {
            field = value
            postInvalidate()
        }

    /** Called from the camera thread with a finished frame. */
    fun post(pixels: IntArray, width: Int, height: Int) {
        val slot = synchronized(lock) { next }
        val target = ring[slot]?.takeIf { it.width == width && it.height == height }
            ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { ring[slot] = it }
        target.setPixels(pixels, 0, width, 0, 0, width, height)
        synchronized(lock) {
            shown = slot
            next = (slot + 1) % ring.size
        }
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        synchronized(lock) {
            val bmp = if (shown >= 0) ring[shown] else null
            if (bmp != null) {
                val scale = minOf(width / bmp.width.toFloat(), height / bmp.height.toFloat())
                val w = (bmp.width * scale).toInt()
                val h = (bmp.height * scale).toInt()
                val left = (width - w) / 2
                val top = (height - h) / 2
                dst.set(left, top, left + w, top + h)
                canvas.drawBitmap(bmp, null, dst, paint)
            }
        }
        message?.let { canvas.drawText(it, width / 2f, height / 2f, textPaint) }
    }
}
