package com.chickengamer.circuitbender

import android.util.Log
import android.util.Size
import androidx.activity.ComponentActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.chickengamer.circuitbender.core.YuvPlanes
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * CameraX analysis stream at a modest 640x480: the pipeline averages it down
 * further, so asking the sensor for more would only cost bandwidth.
 */
class CameraFeed(
    private val activity: ComponentActivity,
    private val rotation: () -> Int,
    private val onError: (String) -> Unit,
    private val onFrame: (YuvPlanes, rotationDegrees: Int) -> Unit,
) {
    private val executor = Executors.newSingleThreadExecutor()
    private var analysis: ImageAnalysis? = null
    private var camera: Camera? = null
    private var torch = false
    var lensFacing = CameraSelector.LENS_FACING_BACK
        private set

    @Volatile var running = false
        private set

    // Reused plane copies; only the analyzer thread touches them.
    private var yBuf = ByteArray(0)
    private var uBuf = ByteArray(0)
    private var vBuf = ByteArray(0)

    fun start() {
        val future = ProcessCameraProvider.getInstance(activity)
        future.addListener({
            val provider = try {
                future.get()
            } catch (e: ExecutionException) {
                Log.e(TAG, "camera provider failed", e)
                onError("CAMERA UNAVAILABLE")
                return@addListener
            }
            if (activity.isDestroyed) return@addListener
            bind(provider, triedOther = false)
        }, ContextCompat.getMainExecutor(activity))
    }

    private fun bind(provider: ProcessCameraProvider, triedOther: Boolean) {
        val selector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(
                ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
            )
            .build()
        val a = ImageAnalysis.Builder()
            .setResolutionSelector(selector)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .build()
        a.targetRotation = rotation()
        a.setAnalyzer(executor) { image -> image.use { deliver(it) } }
        provider.unbindAll()
        try {
            camera = provider.bindToLifecycle(
                activity,
                CameraSelector.Builder().requireLensFacing(lensFacing).build(),
                a,
            )
            analysis = a
            running = true
            camera?.cameraControl?.enableTorch(torch)
        } catch (e: IllegalArgumentException) {
            // No camera on this side: try the other one once.
            Log.w(TAG, "lens $lensFacing unavailable", e)
            if (!triedOther) {
                lensFacing = other(lensFacing)
                bind(provider, triedOther = true)
            } else {
                running = false
                onError("NO CAMERA")
            }
        } catch (e: IllegalStateException) {
            // The activity was torn down while CameraX was starting.
            Log.w(TAG, "bind after destroy", e)
        }
    }

    private fun other(lens: Int) =
        if (lens == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK

    fun flip() {
        lensFacing = other(lensFacing)
        start()
    }

    /** Multiply the zoom by [factor] within the lens's range; returns the new ratio. */
    fun zoomBy(factor: Float): Float? {
        val cam = camera ?: return null
        val state = cam.cameraInfo.zoomState.value ?: return null
        val ratio = (state.zoomRatio * factor).coerceIn(state.minZoomRatio, state.maxZoomRatio)
        cam.cameraControl.setZoomRatio(ratio)
        return ratio
    }

    /** Focus and meter on a point of the sensor image, given as 0..1 across and down. */
    fun focus(x: Float, y: Float) {
        val cam = camera ?: return
        val point = SurfaceOrientedMeteringPointFactory(1f, 1f).createPoint(x, y)
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
            .setAutoCancelDuration(4, TimeUnit.SECONDS)
            .build()
        cam.cameraControl.startFocusAndMetering(action)
    }

    /** Step exposure compensation by [steps]; returns the new value in EV, or null if unsupported. */
    fun exposureBy(steps: Int): Float? {
        val cam = camera ?: return null
        val state = cam.cameraInfo.exposureState
        if (!state.isExposureCompensationSupported) return null
        val range = state.exposureCompensationRange
        val index = (state.exposureCompensationIndex + steps).coerceIn(range.lower, range.upper)
        cam.cameraControl.setExposureCompensationIndex(index)
        return index * state.exposureCompensationStep.toFloat()
    }

    /** Remembered across rebinds, so the light stays on after FLIP. */
    fun setTorch(on: Boolean) {
        torch = on
        camera?.cameraControl?.enableTorch(on)
    }

    fun setRotation(rotation: Int) {
        analysis?.targetRotation = rotation
    }

    /** Run [task] on the camera thread, after any frame in progress. */
    fun post(task: () -> Unit) {
        executor.execute(task)
    }

    fun shutdown() {
        executor.shutdown()
    }

    private fun deliver(image: ImageProxy) {
        val p = image.planes
        yBuf = copy(p[0].buffer, yBuf)
        uBuf = copy(p[1].buffer, uBuf)
        vBuf = copy(p[2].buffer, vBuf)
        val planes = YuvPlanes(
            width = image.width,
            height = image.height,
            y = yBuf,
            yRowStride = p[0].rowStride,
            u = uBuf,
            uOffset = 0,
            v = vBuf,
            vOffset = 0,
            uvRowStride = p[1].rowStride,
            uvPixelStride = p[1].pixelStride,
        )
        try {
            onFrame(planes, image.imageInfo.rotationDegrees)
        } catch (e: RuntimeException) {
            // One bad frame must not take the whole camera down; log it and
            // carry on with the next.
            Log.e(TAG, "frame processing failed", e)
        }
    }

    private fun copy(buffer: java.nio.ByteBuffer, reuse: ByteArray): ByteArray {
        buffer.rewind()
        val n = buffer.remaining()
        // The last row of a plane is often not padded out to the row stride:
        // keep room for a full stride so the sampler can never read past the end.
        val out = if (reuse.size >= n + 4096) reuse else ByteArray(n + 4096)
        buffer.get(out, 0, n)
        return out
    }

    private companion object {
        const val TAG = "CameraFeed"
    }
}
