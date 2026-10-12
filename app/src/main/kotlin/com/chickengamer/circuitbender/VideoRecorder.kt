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
 * Encodes processed frames to an H.264 MP4. Frames are drawn, nearest-
 * neighbour scaled, onto the encoder's input surface; call everything from
 * one thread (the camera thread).
 */
class VideoRecorder(val width: Int, val height: Int, val file: File) {
    private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    private val muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private val surface: android.view.Surface
    private val info = MediaCodec.BufferInfo()
    private val paint = Paint().apply { isFilterBitmap = false }
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

    /** Draw one frame, letterboxed if its shape differs from the video's. */
    fun addFrame(frame: Bitmap) {
        val canvas = surface.lockHardwareCanvas()
        try {
            canvas.drawColor(Color.BLACK)
            val scale = minOf(width / frame.width.toFloat(), height / frame.height.toFloat())
            val w = (frame.width * scale).toInt()
            val h = (frame.height * scale).toInt()
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
        /** Video size for a w x h frame: long side 640, even dimensions. */
        fun sizeFor(w: Int, h: Int): Pair<Int, Int> {
            val k = maxOf(1, 640 / maxOf(w, h))
            return (w * k and 1.inv()) to (h * k and 1.inv())
        }
    }
}
