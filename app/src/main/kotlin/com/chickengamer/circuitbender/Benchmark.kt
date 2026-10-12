package com.chickengamer.circuitbender

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.chickengamer.circuitbender.core.Dither
import com.chickengamer.circuitbender.core.DitherAlgorithm
import com.chickengamer.circuitbender.core.Palettes
import com.chickengamer.circuitbender.core.Pipeline
import com.chickengamer.circuitbender.core.PipelineSettings
import com.chickengamer.circuitbender.core.Resolution
import com.chickengamer.circuitbender.core.Vhs
import com.chickengamer.circuitbender.core.VhsSettings

/**
 * On-device timings for the heavy paths, written to logcat under "CBBench".
 * Started with: adb shell am start -n com.chickengamer.circuitbender/.MainActivity --ez bench true
 * Uses a synthetic test pattern - no camera, nothing on screen. Pauses
 * between measurements so a warming phone does not skew later ones, and
 * logs the phone's thermal status with each.
 */
object Benchmark {
    private const val TAG = "CBBench"

    fun run(context: Context) {
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val src = IntArray(640 * 480) { (0xFF shl 24) or (it * 2654435761L.toInt() ushr 8) }
        val small = IntArray(320 * 240) { src[it] }
        fun time(label: String, reps: Int = 20, block: () -> Unit) {
            Thread.sleep(1500)
            repeat(15) { block() }
            val t0 = SystemClock.elapsedRealtimeNanos()
            repeat(reps) { block() }
            val ms = (SystemClock.elapsedRealtimeNanos() - t0) / 1e6 / reps
            Log.i(TAG, "%-36s %7.1f ms  thermal=%d".format(label, ms, power.currentThermalStatus))
        }
        fun pipeline(res: Resolution, vhs: Boolean): () -> Unit {
            val s = PipelineSettings(
                ditherEnabled = true, resolution = res, vhsEnabled = vhs,
                algorithm = DitherAlgorithm.FLOYD_STEINBERG, palette = Palettes.natural(3),
            )
            val frame = IntArray(s.width * s.height) { src[it % src.size] }
            val p = Pipeline(1)
            return { p.process(frame, s, null, 0) }
        }
        Log.i(TAG, "cores=${Runtime.getRuntime().availableProcessors()}")
        time("vhs 320x240") { Vhs.process(small, 320, 240, VhsSettings(), 1, 0) }
        time("vhs 640x480") { Vhs.process(src, 640, 480, VhsSettings(), 1, 0) }
        com.chickengamer.circuitbender.core.ParallelSwitch.set(false)
        time("vhs 320x240 single thread") { Vhs.process(small, 320, 240, VhsSettings(), 1, 0) }
        time("vhs 320x240 noiseless single") { Vhs.process(small, 320, 240, VhsSettings().noiseless(), 1, 0) }
        com.chickengamer.circuitbender.core.ParallelSwitch.set(true)
        time("vhs 320x240 noiseless") { Vhs.process(small, 320, 240, VhsSettings().noiseless(), 1, 0) }
        time("dither FS 320 27 colours") { Dither.apply(small, 320, 240, DitherAlgorithm.FLOYD_STEINBERG, Palettes.natural(3)) }
        time("dither FS 640 27 colours") { Dither.apply(src, 640, 480, DitherAlgorithm.FLOYD_STEINBERG, Palettes.natural(3)) }
        time("dither FS 640 1-bit") { Dither.apply(src, 640, 480, DitherAlgorithm.FLOYD_STEINBERG, Palettes.ONE_BIT) }
        time("dither JJN 640 27 colours") { Dither.apply(src, 640, 480, DitherAlgorithm.JARVIS_JUDICE_NINKE, Palettes.natural(3)) }
        time("pipeline FS 320 vhs off", 30, pipeline(Resolution.CAMCORDER, false))
        time("pipeline FS 320 vhs on", 30, pipeline(Resolution.CAMCORDER, true))
        time("pipeline FS 640 vhs off", 30, pipeline(Resolution.LARGE, false))
        time("pipeline FS 640 vhs on", 30, pipeline(Resolution.LARGE, true))
        val analog = PipelineSettings()
        val aFrame = IntArray(analog.width * analog.height) { src[it % src.size] }
        val ap = Pipeline(1)
        time("pipeline camcorder 320x480 vhs", 30) { ap.process(aFrame, analog, null, 0) }
        com.chickengamer.circuitbender.core.ParallelSwitch.set(false)
        time("SINGLE vhs 640x480") { Vhs.process(src, 640, 480, VhsSettings(), 1, 0) }
        time("SINGLE pipeline FS 640 vhs on", 30, pipeline(Resolution.LARGE, true))
        val ap2 = Pipeline(1)
        time("SINGLE pipeline camcorder 320x480", 30) { ap2.process(aFrame, analog, null, 0) }
        com.chickengamer.circuitbender.core.ParallelSwitch.set(true)
        Log.i(TAG, "done")
    }
}
