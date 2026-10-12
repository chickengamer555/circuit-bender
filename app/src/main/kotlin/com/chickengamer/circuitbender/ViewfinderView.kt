package com.chickengamer.circuitbender

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/**
 * Shows the processed frame in the area between [setPictureArea]'s bars, at
 * its display shape and turned by the phone's rotation so it reads upright
 * to whoever is holding it. Analog video is drawn smoothly; dithered pixels
 * stay hard-edged blocks.
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
    private var shape = 3 to 4
    private var smooth = true
    private var rotation = 0
    private var topInset = 0
    private var bottomInset = 0
    private val paint = Paint()
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 16f * context.resources.displayMetrics.density
    }
    private val dst = RectF()

    @Volatile var message: String? = null
        set(value) {
            field = value
            postInvalidate()
        }

    /** Keep the picture clear of a top bar and a bottom panel of these heights. */
    fun setPictureArea(top: Int, bottom: Int) {
        if (top == topInset && bottom == bottomInset) return
        topInset = top
        bottomInset = bottom
        invalidate()
    }

    /** Called from the camera thread with a finished, world-upright frame. */
    fun post(pixels: IntArray, width: Int, height: Int, displayW: Int, displayH: Int, smooth: Boolean, rotation: Int) {
        val slot = synchronized(lock) { next }
        val target = ring[slot]?.takeIf { it.width == width && it.height == height }
            ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { ring[slot] = it }
        target.setPixels(pixels, 0, width, 0, 0, width, height)
        synchronized(lock) {
            shown = slot
            next = (slot + 1) % ring.size
            shape = displayW to displayH
            this.smooth = smooth
            this.rotation = rotation
        }
        postInvalidateOnAnimation()
    }

    /** The picture's rectangle before rotation, centred in the free area. */
    private fun layoutPicture() {
        val (sw, sh) = shape
        val turned = rotation % 180 != 0
        val bw = if (turned) sh else sw
        val bh = if (turned) sw else sh
        val areaH = (height - topInset - bottomInset).coerceAtLeast(1)
        val scale = minOf(width / bw.toFloat(), areaH / bh.toFloat())
        val cx = width / 2f
        val cy = topInset + areaH / 2f
        val w = sw * scale
        val h = sh * scale
        dst.set(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
    }

    /**
     * Where a touch at ([x], [y]) falls on the picture, as 0..1 across and
     * down the upright frame, or null if it missed the picture.
     */
    fun toPicture(x: Float, y: Float): Pair<Float, Float>? = synchronized(lock) {
        if (shown < 0) return null
        layoutPicture()
        // Undo the on-screen turn of -rotation about the centre.
        val cx = dst.centerX()
        val cy = dst.centerY()
        val dx = x - cx
        val dy = y - cy
        val (rx, ry) = when (rotation) {
            90 -> -dy to dx
            180 -> -dx to -dy
            270 -> dy to -dx
            else -> dx to dy
        }
        val u = (rx + dst.width() / 2) / dst.width()
        val v = (ry + dst.height() / 2) / dst.height()
        if (u !in 0f..1f || v !in 0f..1f) null else u to v
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        synchronized(lock) {
            val bmp = if (shown >= 0) ring[shown] else null
            if (bmp != null) {
                layoutPicture()
                paint.isFilterBitmap = smooth
                canvas.save()
                canvas.rotate(-rotation.toFloat(), dst.centerX(), dst.centerY())
                canvas.drawBitmap(bmp, null, dst, paint)
                canvas.restore()
            }
        }
        message?.let { canvas.drawText(it, width / 2f, height / 2f, textPaint) }
    }
}
