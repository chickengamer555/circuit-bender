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
    /**
     * Rotation that makes a frame upright for how the phone is held, with the
     * screen locked upright ([rotationDegrees] is then the sensor's own
     * angle). Android's JPEG-orientation rule: back = sensor + device, front =
     * sensor - device.
     */
    fun sampleRotation(rotationDegrees: Int, deviceDegrees: Int, front: Boolean): Int =
        Math.floorMod(if (front) rotationDegrees - deviceDegrees else rotationDegrees + deviceDegrees, 360)

    /**
     * Where point ([u], [v]) of a sampled picture (0..1 across and down) lies
     * on the sensor, as 0..1 of its width and height - the inverse of [sample]
     * for the same rotation and crop.
     */
    fun sensorPoint(u: Float, v: Float, rotationDegrees: Int, sensorW: Int, sensorH: Int, aspectW: Int, aspectH: Int): Pair<Float, Float> {
        val rot = Math.floorMod(rotationDegrees, 360)
        val uw = if (rot % 180 == 0) sensorW else sensorH
        val uh = if (rot % 180 == 0) sensorH else sensorW
        val cropW: Float
        val cropH: Float
        if (uw.toLong() * aspectH > uh.toLong() * aspectW) {
            cropH = uh.toFloat()
            cropW = uh * aspectW / aspectH.toFloat()
        } else {
            cropW = uw.toFloat()
            cropH = uw * aspectH / aspectW.toFloat()
        }
        val ux = (uw - cropW) / 2f + u * cropW
        val uy = (uh - cropH) / 2f + v * cropH
        val (sx, sy) = when (rot) {
            90 -> uy to sensorH - ux
            180 -> sensorW - ux to sensorH - uy
            270 -> sensorW - uy to ux
            else -> ux to uy
        }
        return (sx / sensorW).coerceIn(0f, 1f) to (sy / sensorH).coerceIn(0f, 1f)
    }

    /**
     * Sample an [outWidth] x [outHeight] grid covering a centre crop of shape
     * [aspectW]:[aspectH]. The grid need not be square-pixelled: an analog
     * frame is 320 samples across 480 lines over a 4:3 picture.
     */
    fun sample(
        planes: YuvPlanes,
        rotationDegrees: Int,
        outWidth: Int,
        outHeight: Int,
        aspectW: Int = outWidth,
        aspectH: Int = outHeight,
        dst: IntArray? = null,
    ): IntArray {
        val out = dst?.takeIf { it.size == outWidth * outHeight } ?: IntArray(outWidth * outHeight)
        if (outWidth <= 0 || outHeight <= 0 || planes.width <= 0 || planes.height <= 0) return out
        val rot = Math.floorMod(rotationDegrees, 360)
        val sw = planes.width
        val sh = planes.height
        val uw = if (rot % 180 == 0) sw else sh
        val uh = if (rot % 180 == 0) sh else sw

        // Centre crop of the upright image to the output aspect.
        val cropW: Float
        val cropH: Float
        if (uw.toLong() * aspectH > uh.toLong() * aspectW) {
            cropH = uh.toFloat()
            cropW = uh * aspectW / aspectH.toFloat()
        } else {
            cropW = uw.toFloat()
            cropH = uw * aspectH / aspectW.toFloat()
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

/** How good the camcorder is: samples along each line. */
enum class Quality(val samples: Int, val label: String) {
    TOY(160, "Toy cam"),
    VHS_EP(240, "VHS EP"),
    VHS_SP(320, "VHS SP"),
    HI8(480, "Hi8"),
    SHARP(640, "Sharp"),
}

/** Working "sensor" sizes. */
enum class Resolution(val width: Int, val height: Int, val label: String) {
    LARGE(640, 480, "640x480"),
    BIG(480, 360, "480x360"),
    CAMCORDER(320, 240, "320x240"),
    MID(240, 180, "240x180"),
    LOFI(160, 120, "160x120"),
    GAME_BOY(128, 112, "128x112 Game Boy"),
    TINY(96, 72, "96x72"),
    MICRO(64, 48, "64x48"),
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
    val portrait: Boolean = false,
    val ditherContrast: Float = 1f,
    val quality: Quality = Quality.VHS_SP,
    val ditherBrightness: Float = 0f,
) {
    /**
     * Dithered frames are square pixels at the chosen [resolution]. The
     * camcorder frame is analog: [quality] samples along each of 480 lines
     * (VHS SP is ~330 x 480), so its samples are wider than its lines. Held
     * upright the picture is 3:4 and each line is 3/4 as long.
     */
    val width: Int
        get() = when {
            ditherEnabled -> if (portrait) resolution.height else resolution.width
            portrait -> quality.samples * 3 / 4
            else -> quality.samples
        }
    val height: Int
        get() = when {
            ditherEnabled -> if (portrait) resolution.width else resolution.height
            portrait -> 640
            else -> 480
        }

    /**
     * Pixel height in the overlay's terms: the analog frame always has 480
     * lines for 240 OSD rows, so its rows are drawn twice as tall.
     */
    val pixelAspect: Int get() = if (ditherEnabled) 1 else 2

    /** The picture's shape on screen and in saved photos. */
    val displayWidth: Int get() = if (ditherEnabled) width else if (portrait) 3 else 4
    val displayHeight: Int get() = if (ditherEnabled) height else if (portrait) 4 else 3
}

class Photo(val width: Int, val height: Int, val pixels: IntArray)

/**
 * camera -> bend -> overlay -> VHS -> dither.
 * The camcorder overlay goes in before the tape so it gets the same look as
 * the picture, but only on the viewfinder copy: the clean copy, used for
 * photos and video, never has it.
 */
class Pipeline(private val seed: Long = 1) {
    private val bend = BendEngine(seed)
    private var clean: IntArray? = null
    private var cleanW = 0
    private var cleanH = 0
    private var displayW = 4
    private var displayH = 3
    private var smooth = true

    /**
     * Picture buffers kept between frames: at 640x480 each is over a
     * megabyte, and allocating several per frame keeps a phone's garbage
     * collector - and its CPU - busy. The viewfinder copy alternates between
     * two sets so the previous frame stays intact until the next is done.
     */
    private val buffers = HashMap<String, IntArray>()
    private var parity = 0

    private fun buffer(name: String, size: Int): IntArray =
        buffers[name]?.takeIf { it.size == size } ?: IntArray(size).also { buffers[name] = it }

    /**
     * Render one frame and return the viewfinder picture. With [clean] set,
     * also keep a copy for [capture]: overlay-free, or - with [keepOverlay] -
     * exactly the viewfinder picture. The returned array is reused two
     * frames later; [src] is never modified.
     */
    fun process(
        src: IntArray,
        settings: PipelineSettings,
        osd: OsdState?,
        frame: Long,
        clean: Boolean = false,
        keepOverlay: Boolean = false,
    ): IntArray {
        val w = settings.width
        val h = settings.height
        val n = w * h
        require(src.size == n) { "frame is ${src.size} px, expected ${w}x$h" }
        parity = 1 - parity
        val set = "p$parity"

        val bent = if (settings.bendEnabled) bend.process(src, w, h, settings.bend, frame) else src
        val preview = if (osd != null) {
            val withOverlay = buffer("$set-overlay", n)
            System.arraycopy(bent, 0, withOverlay, 0, n)
            Osd.drawStamp(withOverlay, w, h, osd, aspect = settings.pixelAspect)
            Osd.drawViewfinder(withOverlay, w, h, osd, aspect = settings.pixelAspect)
            finish(withOverlay, settings, w, h, frame, set)
        } else {
            finish(bent, settings, w, h, frame, set)
        }
        this.clean = when {
            !clean -> null
            osd == null || keepOverlay -> preview
            else -> finish(bent, settings, w, h, frame, "clean")
        }
        cleanW = w
        cleanH = h
        displayW = settings.displayWidth
        displayH = settings.displayHeight
        smooth = !settings.ditherEnabled
        return if (preview === src) preview.copyOf() else preview
    }

    private fun finish(px: IntArray, settings: PipelineSettings, w: Int, h: Int, frame: Long, set: String): IntArray {
        val n = w * h
        var out = px
        if (settings.vhsEnabled) {
            out = Vhs.process(out, w, h, scaledVhs(settings.vhs, w, h), seed, frame, buffer("$set-vhs", n))
        }
        if (settings.ditherEnabled) {
            if (settings.ditherContrast != 1f || settings.ditherBrightness != 0f) {
                out = tone(out, settings.ditherContrast, settings.ditherBrightness, buffer("$set-tone", n))
            }
            out = Dither.apply(out, w, h, settings.algorithm, settings.palette, buffer("$set-dither", n))
        }
        return out
    }

    /**
     * The last overlay-free frame at its true shape with [longSide] pixels on
     * the long edge: smoothly resampled for the analog camcorder picture,
     * hard-edged for dither.
     */
    fun capturePhoto(longSide: Int): Photo {
        val src = clean ?: return Photo(0, 0, IntArray(0))
        val w: Int
        val h: Int
        if (displayW >= displayH) {
            w = longSide
            h = longSide * displayH / displayW
        } else {
            h = longSide
            w = longSide * displayW / displayH
        }
        val px = if (smooth) Resample.bilinear(src, cleanW, cleanH, w, h) else Resample.nearest(src, cleanW, cleanH, w, h)
        return Photo(w, h, px)
    }

    private fun tone(px: IntArray, c: Float, b: Float, dst: IntArray): IntArray {
        fun ch(v: Int) = ((v - 128) * c + 128f + b).toInt().coerceIn(0, 255)
        for (i in px.indices) {
            val p = px[i]
            dst[i] = (0xFF shl 24) or (ch((p shr 16) and 0xFF) shl 16) or (ch((p shr 8) and 0xFF) shl 8) or ch(p and 0xFF)
        }
        return dst
    }

    /** The last overlay-free frame (see [process]), scaled up by [scale] with hard pixel edges. */
    fun capture(scale: Int): Photo {
        val src = clean ?: return Photo(0, 0, IntArray(0))
        // At scale 1 hand over the frame itself (valid until the next
        // process call) rather than copying a megabyte per video frame.
        if (scale <= 1) return Photo(cleanW, cleanH, src)
        val k = scale
        val w = cleanW * k
        val h = cleanH * k
        val out = IntArray(w * h)
        for (y in 0 until h) {
            val row = (y / k) * cleanW
            for (x in 0 until w) out[y * w + x] = src[row + x / k]
        }
        return Photo(w, h, out)
    }

    /** The VHS numbers are for 320x240; keep them physically the same at other sizes. */
    private fun scaledVhs(v: VhsSettings, w: Int, h: Int): VhsSettings {
        if (w == 320 && h == 240) return v
        // Distances along a line scale with samples per line; line counts
        // with lines per field (240).
        val sx = w / 320f
        return v.copy(
            chromaDivisor = maxOf(2, (v.chromaDivisor * sx).roundToInt()),
            chromaDelayPx = v.chromaDelayPx * sx,
            jitterPx = v.jitterPx * sx,
            headSwitchLines = if (v.headSwitchLines == 0) 0 else maxOf(1, v.headSwitchLines * h / 240),
        )
    }
}
