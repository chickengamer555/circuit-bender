package com.chickengamer.circuitbender.core

/** Scaling a finished frame to its saved size. */
object Resample {
    fun nearest(src: IntArray, sw: Int, sh: Int, dw: Int, dh: Int): IntArray {
        val out = IntArray(dw * dh)
        for (y in 0 until dh) {
            val row = (y * sh / dh) * sw
            for (x in 0 until dw) out[y * dw + x] = src[row + x * sw / dw]
        }
        return out
    }

    /** Bilinear, sampling at pixel centres. */
    fun bilinear(src: IntArray, sw: Int, sh: Int, dw: Int, dh: Int): IntArray {
        val out = IntArray(dw * dh)
        if (sw <= 0 || sh <= 0) return out
        Parallel.rows(dh) { from, to ->
            for (y in from until to) {
                val fy = ((y + 0.5f) * sh / dh - 0.5f).coerceIn(0f, (sh - 1).toFloat())
                val y0 = fy.toInt()
                val y1 = minOf(sh - 1, y0 + 1)
                val ty = fy - y0
                for (x in 0 until dw) {
                    val fx = ((x + 0.5f) * sw / dw - 0.5f).coerceIn(0f, (sw - 1).toFloat())
                    val x0 = fx.toInt()
                    val x1 = minOf(sw - 1, x0 + 1)
                    val tx = fx - x0
                    val a = src[y0 * sw + x0]
                    val b = src[y0 * sw + x1]
                    val c = src[y1 * sw + x0]
                    val d = src[y1 * sw + x1]
                    var px = 0xFF shl 24
                    for (shift in intArrayOf(16, 8, 0)) {
                        val top = ((a shr shift) and 0xFF) * (1 - tx) + ((b shr shift) and 0xFF) * tx
                        val bottom = ((c shr shift) and 0xFF) * (1 - tx) + ((d shr shift) and 0xFF) * tx
                        px = px or ((top * (1 - ty) + bottom * ty + 0.5f).toInt().coerceIn(0, 255) shl shift)
                    }
                    out[y * dw + x] = px
                }
            }
        }
        return out
    }
}
