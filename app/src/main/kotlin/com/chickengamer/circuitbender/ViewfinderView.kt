package com.chickengamer.circuitbender

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.view.View

/**
 * Shows the processed frame letterboxed at its own aspect ratio, scaled with
 * nearest-neighbour so every low-res pixel stays a hard-edged block.
 */
class ViewfinderView(context: Context) : View(context) {
    private val lock = Any()
    private var front: Bitmap? = null
    private var back: Bitmap? = null
    private val paint = Paint().apply { isFilterBitmap = false }
    private val dst = Rect()

    /** Called from the camera thread with a finished frame. */
    fun post(pixels: IntArray, width: Int, height: Int) {
        val target = synchronized(lock) {
            back?.takeIf { it.width == width && it.height == height && !it.isRecycled }
                ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        }
        target.setPixels(pixels, 0, width, 0, 0, width, height)
        synchronized(lock) {
            back = front
            front = target
        }
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        synchronized(lock) {
            val bmp = front ?: return
            val scale = minOf(width / bmp.width.toFloat(), height / bmp.height.toFloat())
            val w = (bmp.width * scale).toInt()
            val h = (bmp.height * scale).toInt()
            val left = (width - w) / 2
            val top = (height - h) / 2
            dst.set(left, top, left + w, top + h)
            canvas.drawBitmap(bmp, null, dst, paint)
        }
    }
}
