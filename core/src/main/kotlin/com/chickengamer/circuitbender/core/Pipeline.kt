package com.chickengamer.circuitbender.core

import kotlin.math.roundToInt

/**
 * A YUV_420_888 camera frame as Android delivers it: three planes that may be
 * padded (row strides) and may interleave U and V (pixel stride 2). U and V
 * can be views of one shared array, located by their offsets.
 */
class YuvPlanes(
    val width: Int,
    val height: Int,
    val y: ByteArray,
    val yRowStride: Int,
    val u: ByteArray,
    val uOffset: Int,
    val v: ByteArray,
    val vOffset: Int,
    val uvRowStride: Int,
    val uvPixelStride: Int,
)

/**
 * The "downgrade": crop the phone's frame to the target aspect, turn it
 * upright, and box-average it down to a camcorder-sized sensor.
 */
object CameraSampler {
    fun sample(planes: YuvPlanes, rotationDegrees: Int, outWidth: Int, outHeight: Int): IntArray {
        val out = IntArray(outWidth * outHeight)
        if (outWidth <= 0 || outHeight <= 0 || planes.width <= 0 || planes.height <= 0) return out
        val rot = Math.floorMod(rotationDegrees, 360)
        val sw = planes.width
        val sh = planes.height
        val uw = if (rot % 180 == 0) sw else sh
        val uh = if (rot % 180 == 0) sh else sw

        // Centre crop of the upright image to the output aspect.
        val cropW: Float
        val cropH: Float
        if (uw.toLong() * outHeight > uh.toLong() * outWidth) {
            cropH = uh.toFloat()
            cropW = uh * outWidth / outHeight.toFloat()
        } else {
            cropW = uw.toFloat()
            cropH = uw * outHeight / outWidth.toFloat()
        }
        val cx0 = (uw - cropW) / 2f
        val cy0 = (uh - cropH) / 2f
        val bx = cropW / outWidth
        val by = cropH / outHeight
        // At most 4x4 taps per output pixel keeps big sensors cheap.
        val tx = bx.roundToInt().coerceIn(1, 4)
        val ty = by.roundToInt().coerceIn(1, 4)

        for (oy in 0 until outHeight) for (ox in 0 until outWidth) {
            var ys = 0
            var us = 0
            var vs = 0
            for (j in 0 until ty) for (i in 0 until tx) {
                val ux = (cx0 + ox * bx + (i + 0.5f) * bx / tx).toInt().coerceIn(0, uw - 1)
                val uy = (cy0 + oy * by + (j + 0.5f) * by / ty).toInt().coerceIn(0, uh - 1)
                val sx: Int
                val sy: Int
                when (rot) {
                    90 -> { sx = uy; sy = sh - 1 - ux }
                    180 -> { sx = sw - 1 - ux; sy = sh - 1 - uy }
                    270 -> { sx = sw - 1 - uy; sy = ux }
                    else -> { sx = ux; sy = uy }
                }
                ys += planes.y[sy * planes.yRowStride + sx].toInt() and 0xFF
                val c = (sy / 2) * planes.uvRowStride + (sx / 2) * planes.uvPixelStride
                us += planes.u[planes.uOffset + c].toInt() and 0xFF
                vs += planes.v[planes.vOffset + c].toInt() and 0xFF
            }
            val n = (tx * ty).toFloat()
            val yy = ys / n
            val u = us / n - 128f
            val v = vs / n - 128f
            val r = (yy + 1.402f * v).roundToInt().coerceIn(0, 255)
            val g = (yy - 0.344136f * u - 0.714136f * v).roundToInt().coerceIn(0, 255)
            val b = (yy + 1.772f * u).roundToInt().coerceIn(0, 255)
            out[oy * outWidth + ox] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return out
    }
}

/** Working "sensor" sizes. */
enum class Resolution(val width: Int, val height: Int, val label: String) {
    CAMCORDER(320, 240, "VHS 320x240"),
    LOFI(160, 120, "Toy cam 160x120"),
    GAME_BOY(128, 112, "Game Boy 128x112"),
}

data class PipelineSettings(
    val resolution: Resolution = Resolution.CAMCORDER,
    val vhsEnabled: Boolean = true,
    val vhs: VhsSettings = VhsSettings(),
    val bendEnabled: Boolean = false,
    val bend: BendSettings = BendSettings(),
    val ditherEnabled: Boolean = false,
    val algorithm: DitherAlgorithm = DitherAlgorithm.BAYER_4,
    val palette: Palette = Palettes.GAME_BOY,
)

class Photo(val width: Int, val height: Int, val pixels: IntArray)

/**
 * camera -> bend -> date stamp -> VHS -> dither -> viewfinder overlay.
 * The date stamp goes in before the tape (camcorders record it into the
 * video, so it bleeds like everything else); the viewfinder indicators go on
 * last, after dithering, in palette colours when dithering is on.
 */
class Pipeline(private val seed: Long = 1) {
    private val bend = BendEngine(seed)
    private var last: IntArray? = null
    private var lastW = 0
    private var lastH = 0

    fun process(src: IntArray, settings: PipelineSettings, osd: OsdState, frame: Long): IntArray {
        val w = settings.resolution.width
        val h = settings.resolution.height
        require(src.size == w * h) { "frame is ${src.size} px, expected ${w}x$h" }

        var px = if (settings.bendEnabled) bend.process(src, w, h, settings.bend, frame) else src.copyOf()
        Osd.drawStamp(px, w, h, osd)
        if (settings.vhsEnabled) px = Vhs.process(px, w, h, scaledVhs(settings.vhs, w, h), seed, frame)
        val palette = if (settings.ditherEnabled) settings.palette else null
        if (palette != null) px = Dither.apply(px, w, h, settings.algorithm, palette)
        Osd.drawViewfinder(px, w, h, osd, palette)

        last = px
        lastW = w
        lastH = h
        return px
    }

    /** The last viewfinder frame, scaled up by [scale] with hard pixel edges. */
    fun capture(scale: Int): Photo {
        val src = last ?: return Photo(0, 0, IntArray(0))
        val k = maxOf(1, scale)
        val w = lastW * k
        val h = lastH * k
        val out = IntArray(w * h)
        for (y in 0 until h) {
            val row = (y / k) * lastW
            for (x in 0 until w) out[y * w + x] = src[row + x / k]
        }
        return Photo(w, h, out)
    }

    /** The VHS numbers are for 320x240; keep them physically the same at other sizes. */
    private fun scaledVhs(v: VhsSettings, w: Int, h: Int): VhsSettings {
        if (w == 320 && h == 240) return v
        val sx = w / 320f
        return v.copy(
            chromaDivisor = maxOf(2, (v.chromaDivisor * sx).roundToInt()),
            chromaDelayPx = v.chromaDelayPx * sx,
            jitterPx = v.jitterPx * sx,
            headSwitchLines = if (v.headSwitchLines == 0) 0 else maxOf(1, v.headSwitchLines * h / 240),
        )
    }
}
