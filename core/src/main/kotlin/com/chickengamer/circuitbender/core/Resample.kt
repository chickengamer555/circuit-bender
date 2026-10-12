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

    /** Bilinear, sampling at pixel centres; 8-bit fixed-point weights from per-column tables. */
    fun bilinear(src: IntArray, sw: Int, sh: Int, dw: Int, dh: Int, out: IntArray = IntArray(dw * dh)): IntArray {
        if (sw <= 0 || sh <= 0 || dw <= 0 || dh <= 0) return out
        val x0 = IntArray(dw)
        val x1 = IntArray(dw)
        val wx = IntArray(dw)
        for (x in 0 until dw) {
            val fx = ((x + 0.5f) * sw / dw - 0.5f).coerceIn(0f, (sw - 1).toFloat())
            x0[x] = fx.toInt()
            x1[x] = minOf(sw - 1, x0[x] + 1)
            wx[x] = ((fx - x0[x]) * 256f + 0.5f).toInt()
        }
        Parallel.rows(dh) { from, to ->
            for (y in from until to) {
                val fy = ((y + 0.5f) * sh / dh - 0.5f).coerceIn(0f, (sh - 1).toFloat())
                val y0 = fy.toInt()
                val r0 = y0 * sw
                val r1 = minOf(sh - 1, y0 + 1) * sw
                val wy = ((fy - y0) * 256f + 0.5f).toInt()
                val o = y * dw
                for (x in 0 until dw) {
                    val a = src[r0 + x0[x]]
                    val b = src[r0 + x1[x]]
                    val c = src[r1 + x0[x]]
                    val d = src[r1 + x1[x]]
                    val tx = wx[x]
                    out[o + x] = (0xFF shl 24) or
                        (mix(a, b, c, d, 16, tx, wy) shl 16) or
                        (mix(a, b, c, d, 8, tx, wy) shl 8) or
                        mix(a, b, c, d, 0, tx, wy)
                }
            }
        }
        return out
    }

    /** One channel of a 2x2 blend with weights out of 256. */
    private fun mix(a: Int, b: Int, c: Int, d: Int, shift: Int, tx: Int, ty: Int): Int {
        val top = ((a shr shift) and 0xFF) * (256 - tx) + ((b shr shift) and 0xFF) * tx
        val bottom = ((c shr shift) and 0xFF) * (256 - tx) + ((d shr shift) and 0xFF) * tx
        return (top * (256 - ty) + bottom * ty + 32768) shr 16
    }
}
