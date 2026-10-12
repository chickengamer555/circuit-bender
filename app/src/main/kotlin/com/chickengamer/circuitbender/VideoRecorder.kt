package com.chickengamer.circuitbender

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File

/**
 * Encodes processed frames to an H.264 MP4. Frames are drawn, scaled,
 * onto the encoder's input surface; call everything from
 * one thread (the camera thread).
 */
class VideoRecorder(val width: Int, val height: Int, val file: File) {
    private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    private val muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private val surface: android.view.Surface
    private val info = MediaCodec.BufferInfo()
    private val paint = Paint()
    private val dst = Rect()
    private var track = -1
    private var muxing = false

    init {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 6_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        surface = codec.createInputSurface()
        codec.start()
    }

    /**
     * Draw one frame at its display shape [displayW]:[displayH], letterboxed
     * if that differs from the video's; [smooth] for analog, hard-edged for
     * dither.
     */
    fun addFrame(frame: Bitmap, displayW: Int, displayH: Int, smooth: Boolean) {
        val canvas = surface.lockHardwareCanvas()
        try {
            canvas.drawColor(Color.BLACK)
            paint.isFilterBitmap = smooth
            val scale = minOf(width / displayW.toFloat(), height / displayH.toFloat())
            val w = (displayW * scale).toInt()
            val h = (displayH * scale).toInt()
            dst.set((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
            canvas.drawBitmap(frame, null, dst, paint)
        } finally {
            surface.unlockCanvasAndPost(canvas)
        }
        drain(false)
    }

    /** Finish the file. Returns false if no frame ever reached it. */
    fun finish(): Boolean {
        try {
            codec.signalEndOfInputStream()
            drain(true)
        } finally {
            codec.stop()
            codec.release()
            surface.release()
            if (muxing) muxer.stop()
            muxer.release()
        }
        return muxing
    }

    private fun drain(endOfStream: Boolean) {
        while (true) {
            val index = codec.dequeueOutputBuffer(info, if (endOfStream) 10_000 else 0)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!endOfStream) return
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxing = true
                }
                index >= 0 -> {
                    val buffer = codec.getOutputBuffer(index)
                    val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (buffer != null && muxing && !config && info.size > 0) {
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        muxer.writeSampleData(track, buffer, info)
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    companion object {
        /** Video size for a picture shaped w:h - long side 640, even dimensions. */
        fun sizeFor(w: Int, h: Int): Pair<Int, Int> {
            val long = 640
            val (vw, vh) = if (w >= h) long to long * h / w else long * w / h to long
            return (vw and 1.inv()) to (vh and 1.inv())
        }
    }
}
