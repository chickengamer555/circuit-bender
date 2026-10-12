package com.chickengamer.circuitbender

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

/**
 * Encodes processed frames to an H.264 MP4, with sound from the microphone
 * when [withAudio] is set. Frames are drawn, scaled, onto the encoder's input
 * surface from the camera thread; sound is captured and encoded on its own
 * thread. Both share one muxer.
 */
class VideoRecorder(val width: Int, val height: Int, val file: File, withAudio: Boolean) {
    private val muxer = SharedMuxer(file, tracks = if (withAudio) 2 else 1)
    private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    private val surface: android.view.Surface
    private val info = MediaCodec.BufferInfo()
    private val paint = Paint()
    private val dst = Rect()
    private var track = -1
    private val audio: AudioCapture?

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
        audio = if (withAudio) AudioCapture(muxer).also { it.start() } else null
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

    /** Finish the file. Returns false if nothing usable reached it. */
    fun finish(): Boolean {
        try {
            audio?.stop()
            codec.signalEndOfInputStream()
            drain(true)
        } finally {
            codec.stop()
            codec.release()
            surface.release()
        }
        return muxer.finish()
    }

    private fun drain(endOfStream: Boolean) {
        while (true) {
            val index = codec.dequeueOutputBuffer(info, if (endOfStream) 10_000 else 0)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!endOfStream) return
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> track = muxer.addTrack(codec.outputFormat)
                index >= 0 -> {
                    val buffer = codec.getOutputBuffer(index)
                    val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (buffer != null && track >= 0 && !config && info.size > 0) muxer.write(track, buffer, info)
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

/**
 * One MP4 fed from two threads. A muxer can only start once every track is
 * added, so samples that arrive earlier (the first video frames, usually) are
 * held and written as soon as it starts.
 */
private class SharedMuxer(file: File, private val tracks: Int) {
    private val muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private val pending = ArrayList<Triple<Int, ByteBuffer, MediaCodec.BufferInfo>>()
    private var added = 0
    private var started = false
    private var written = 0

    @Synchronized
    fun addTrack(format: MediaFormat): Int {
        val index = muxer.addTrack(format)
        if (++added == tracks) {
            muxer.start()
            started = true
            for ((t, b, i) in pending) {
                muxer.writeSampleData(t, b, i)
                written++
            }
            pending.clear()
        }
        return index
    }

    @Synchronized
    fun write(track: Int, data: ByteBuffer, info: MediaCodec.BufferInfo) {
        data.position(info.offset)
        data.limit(info.offset + info.size)
        if (started) {
            muxer.writeSampleData(track, data, info)
            written++
            return
        }
        // Copy: the encoder reuses its buffer as soon as we return.
        val copy = ByteBuffer.allocateDirect(info.size).put(data).apply { flip() }
        val meta = MediaCodec.BufferInfo().apply { set(0, info.size, info.presentationTimeUs, info.flags) }
        pending += Triple(track, copy, meta)
    }

    /** Close the file; true if it holds any samples. */
    @Synchronized
    fun finish(): Boolean {
        try {
            if (started) muxer.stop()
        } catch (e: IllegalStateException) {
            Log.e("SharedMuxer", "stop failed", e)
            written = 0
        } finally {
            muxer.release()
        }
        return started && written > 0
    }
}

/**
 * Microphone to AAC on its own thread. Uses the CAMCORDER source, which
 * phones tune for recording alongside video. Timestamps come from the same
 * monotonic clock as the video frames, so sound and picture line up.
 */
private class AudioCapture(private val muxer: SharedMuxer) {
    private val rate = 44_100
    private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    @Volatile private var running = true
    private var thread: Thread? = null

    @SuppressLint("MissingPermission") // only constructed after RECORD_AUDIO is granted
    private val record = AudioRecord(
        MediaRecorder.AudioSource.CAMCORDER,
        rate,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
        maxOf(AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), 8192) * 2,
    )

    init {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
    }

    fun start() {
        record.startRecording()
        thread = Thread({ loop() }, "circuitbender-audio").also { it.start() }
    }

    fun stop() {
        running = false
        thread?.join(2000)
        try {
            record.stop()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "mic already stopped", e)
        }
        record.release()
        codec.stop()
        codec.release()
    }

    private fun loop() {
        val info = MediaCodec.BufferInfo()
        val pcm = ByteArray(4096)
        var track = -1
        var samples = 0L
        var startUs = -1L
        var ending = false
        while (true) {
            if (!ending) {
                val index = codec.dequeueInputBuffer(10_000)
                if (index >= 0) {
                    val input = codec.getInputBuffer(index)!!
                    val read = record.read(pcm, 0, minOf(pcm.size, input.capacity()))
                    if (startUs < 0) startUs = System.nanoTime() / 1000
                    val pts = startUs + samples * 1_000_000L / rate
                    if (!running) {
                        codec.queueInputBuffer(index, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        ending = true
                    } else if (read > 0) {
                        input.clear()
                        input.put(pcm, 0, read)
                        codec.queueInputBuffer(index, 0, read, pts, 0)
                        samples += read / 2
                    } else {
                        codec.queueInputBuffer(index, 0, 0, pts, 0)
                    }
                }
            }
            val out = codec.dequeueOutputBuffer(info, 10_000)
            when {
                out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> track = muxer.addTrack(codec.outputFormat)
                out >= 0 -> {
                    val buffer = codec.getOutputBuffer(out)
                    val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (buffer != null && track >= 0 && !config && info.size > 0) muxer.write(track, buffer, info)
                    codec.releaseOutputBuffer(out, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    private companion object {
        const val TAG = "AudioCapture"
    }
}
