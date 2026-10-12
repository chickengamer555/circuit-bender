package com.chickengamer.circuitbender

import android.util.Log
import android.util.Size
import androidx.activity.ComponentActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.chickengamer.circuitbender.core.YuvPlanes
import java.util.concurrent.Executors

/**
 * CameraX analysis stream at a modest 640x480: the pipeline averages it down
 * further, so asking the sensor for more would only cost bandwidth.
 */
class CameraFeed(
    private val activity: ComponentActivity,
    private val onFrame: (YuvPlanes, rotationDegrees: Int) -> Unit,
) {
    private val executor = Executors.newSingleThreadExecutor()
    private var analysis: ImageAnalysis? = null
    private var camera: Camera? = null
    var lensFacing = CameraSelector.LENS_FACING_BACK
        private set

    // Reused plane copies; only the analyzer thread touches them.
    private var yBuf = ByteArray(0)
    private var uBuf = ByteArray(0)
    private var vBuf = ByteArray(0)

    fun start() {
        val future = ProcessCameraProvider.getInstance(activity)
        future.addListener({
            val provider = future.get()
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
            a.targetRotation = activity.display?.rotation ?: 0
            a.setAnalyzer(executor) { image -> image.use { deliver(it) } }
            provider.unbindAll()
            try {
                camera = provider.bindToLifecycle(
                    activity,
                    CameraSelector.Builder().requireLensFacing(lensFacing).build(),
                    a,
                )
                analysis = a
            } catch (e: IllegalArgumentException) {
                // No camera on this side (e.g. no front camera): fall back to the other.
                Log.w(TAG, "lens $lensFacing unavailable", e)
                if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
                    lensFacing = CameraSelector.LENS_FACING_BACK
                    start()
                }
            }
        }, ContextCompat.getMainExecutor(activity))
    }

    fun flip() {
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
        start()
    }

    fun setTorch(on: Boolean) {
        camera?.cameraControl?.enableTorch(on)
    }

    fun setRotation(rotation: Int) {
        analysis?.targetRotation = rotation
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
        onFrame(planes, image.imageInfo.rotationDegrees)
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
