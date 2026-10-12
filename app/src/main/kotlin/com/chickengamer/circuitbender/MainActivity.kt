package com.chickengamer.circuitbender

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.chickengamer.circuitbender.core.CameraSampler
import com.chickengamer.circuitbender.core.Knobs
import com.chickengamer.circuitbender.core.OsdState
import com.chickengamer.circuitbender.core.Pipeline
import com.chickengamer.circuitbender.core.PipelineSettings
import com.chickengamer.circuitbender.core.YuvPlanes
import java.io.File
import java.time.LocalDateTime
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : ComponentActivity() {
    // --- What the knobs are set to (read by the camera thread) -----------------
    @Volatile private var bendOn = false
    @Volatile private var noise = 0.3f
    @Volatile private var tear = 0.15f
    @Volatile private var colour = 0f
    @Volatile private var ditherOn = false
    @Volatile private var style = 0f
    @Volatile private var palette = 0.35f
    @Volatile private var pixel = 0f
    @Volatile private var portrait = false
    @Volatile private var battery = 3

    // --- Capture state ---------------------------------------------------------
    private val photoRequested = AtomicBoolean(false)
    @Volatile private var wantRecording = false
    private var recorder: VideoRecorder? = null // camera thread only
    private var recordFrame: Bitmap? = null // camera thread only
    @Volatile private var recordStartedAt = 0L
    private var frame = 0L

    private val pipeline = Pipeline(seed = System.nanoTime())
    private val io = Executors.newSingleThreadExecutor()

    private lateinit var feed: CameraFeed
    private lateinit var viewfinder: ViewfinderView
    private lateinit var root: FrameLayout
    private val controls = mutableListOf<View>()
    private var videoButton: TextView? = null
    private var status: TextView? = null
    private var lastUri: Uri? = null
    private var lastMime = "image/png"

    private val askCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            viewfinder.message = null
            feed.start()
        } else {
            viewfinder.message = "NO CAMERA PERMISSION - TAP TO RETRY"
        }
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(id: Int) {}
        override fun onDisplayRemoved(id: Int) {}
        override fun onDisplayChanged(id: Int) = feed.setRotation(displayRotation())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        viewfinder = ViewfinderView(this)
        root = FrameLayout(this).apply { setBackgroundColor(0xFF000000.toInt()) }
        root.addView(viewfinder, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)
        portrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT

        feed = CameraFeed(this, ::displayRotation, { msg -> runOnUiThread { viewfinder.message = msg } }, ::onFrame)
        buildControls()
        viewfinder.setOnClickListener {
            if (!feed.running && !granted()) askCamera.launch(Manifest.permission.CAMERA)
        }
        if (granted()) feed.start() else askCamera.launch(Manifest.permission.CAMERA)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        portrait = newConfig.orientation == Configuration.ORIENTATION_PORTRAIT
        buildControls()
        feed.setRotation(displayRotation())
    }

    override fun onResume() {
        super.onResume()
        (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).registerDisplayListener(displayListener, null)
        feed.setRotation(displayRotation())
        val pct = (getSystemService(Context.BATTERY_SERVICE) as BatteryManager).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        battery = when {
            pct > 66 -> 3
            pct > 33 -> 2
            pct > 10 -> 1
            else -> 0
        }
    }

    override fun onPause() {
        super.onPause()
        (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).unregisterDisplayListener(displayListener)
        // The camera stops delivering frames while paused: close the file now.
        if (wantRecording) {
            wantRecording = false
            feed.post { finishRecording() }
            updateVideoButton()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        feed.shutdown()
        io.shutdown()
    }

    private fun granted() = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun displayRotation(): Int = display?.rotation ?: 0

    // --- Camera thread -------------------------------------------------------------

    private fun settings(): PipelineSettings = PipelineSettings(
        resolution = Knobs.pixel(if (ditherOn) pixel else 0f),
        portrait = portrait,
        bendEnabled = bendOn,
        bend = Knobs.bend(noise, tear, colour),
        ditherEnabled = ditherOn,
        algorithm = Knobs.ditherStyle(style),
        palette = Knobs.palette(palette),
    )

    private fun onFrame(planes: YuvPlanes, rotation: Int) {
        val s = settings()
        val w = s.width
        val h = s.height
        val px = CameraSampler.sample(planes, rotation, w, h)
        if (feed.lensFacing == CameraSelector.LENS_FACING_FRONT) {
            for (y in 0 until h) px.reverse(y * w, y * w + w)
        }

        if (wantRecording && recorder == null) startRecording(w, h)
        if (!wantRecording && recorder != null) finishRecording()
        val recording = recorder != null
        val takePhoto = photoRequested.getAndSet(false)

        val now = System.currentTimeMillis()
        val osd = OsdState(
            recording = recording,
            blinkOn = (now / 500) % 2 == 0L,
            tapeSpeed = "SP",
            battery = battery,
            counterSeconds = if (recording) (now - recordStartedAt) / 1000 else 0,
            dateTime = LocalDateTime.now(),
        )
        viewfinder.post(pipeline.process(px, s, osd, frame++, clean = takePhoto || recording), w, h)

        if (recording) {
            val clean = pipeline.capture(1)
            val bmp = recordFrame?.takeIf { it.width == clean.width && it.height == clean.height }
                ?: Bitmap.createBitmap(clean.width, clean.height, Bitmap.Config.ARGB_8888).also { recordFrame = it }
            bmp.setPixels(clean.pixels, 0, clean.width, 0, 0, clean.width, clean.height)
            recorder?.addFrame(bmp)
        }
        if (takePhoto) {
            val photo = pipeline.capture(maxOf(1, 1280 / maxOf(w, h)))
            io.execute {
                val uri = PhotoSaver.save(this, photo)
                runOnUiThread { saved(uri, "image/png", "PHOTO SAVED") }
            }
        }
    }

    private fun startRecording(w: Int, h: Int) {
        val (vw, vh) = VideoRecorder.sizeFor(w, h)
        try {
            recorder = VideoRecorder(vw, vh, File(cacheDir, "rec_${System.nanoTime()}.mp4"))
            recordStartedAt = System.currentTimeMillis()
        } catch (e: Exception) {
            // MediaCodec reports failures as several unrelated exception types.
            Log.e(TAG, "cannot start recording", e)
            wantRecording = false
            runOnUiThread {
                updateVideoButton()
                Toast.makeText(this, "Could not start recording", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun finishRecording() {
        val rec = recorder ?: return
        recorder = null
        val ok = try {
            rec.finish()
        } catch (e: IllegalStateException) {
            Log.e(TAG, "finishing recording failed", e)
            false
        }
        io.execute {
            val uri = if (ok) {
                PhotoSaver.saveVideo(this, rec.file)
            } else {
                rec.file.delete()
                null
            }
            runOnUiThread { saved(uri, "video/mp4", "VIDEO SAVED") }
        }
    }

    // --- UI ------------------------------------------------------------------------

    /** Buttons down the right in landscape, along the bottom in portrait; knobs beside/above them. */
    private fun buildControls() {
        controls.forEach { root.removeView(it) }
        controls.clear()

        val buttons = LinearLayout(this).apply {
            orientation = if (portrait) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            val p = Retro.dp(this@MainActivity, 6f)
            setPadding(p, p, p, p)
        }
        fun add(v: View) {
            val lp = if (portrait) {
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            } else {
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            }
            val m = Retro.dp(this, 3f)
            lp.setMargins(m, m, m, m)
            buttons.addView(v, lp)
        }

        val knobs = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = Retro.dp(this@MainActivity, 10f)
            setPadding(p, p / 2, p, p / 2)
        }
        val knobPanel = ScrollView(this).apply {
            setBackgroundColor(Retro.PANEL)
            addView(knobs)
        }
        fun refreshKnobs() {
            knobs.removeAllViews()
            if (bendOn) {
                knobs.addView(Retro.label(this, "BEND", Retro.RED, 12f))
                knobs.addView(Retro.knob(this, "NOISE", noise, ::percent) { noise = it })
                knobs.addView(Retro.knob(this, "TEAR", tear, ::percent) { tear = it })
                knobs.addView(Retro.knob(this, "COLOUR", colour, ::percent) { colour = it })
            }
            if (ditherOn) {
                knobs.addView(Retro.label(this, "DITHER", Retro.GREEN, 12f))
                knobs.addView(Retro.knob(this, "STYLE", style, { Knobs.ditherStyle(it).label }) { style = it })
                knobs.addView(Retro.knob(this, "PALETTE", palette, { Knobs.palette(it).name }) { palette = it })
                knobs.addView(Retro.knob(this, "PIXEL", pixel, { Knobs.pixel(it).label }) { pixel = it })
            }
            knobPanel.visibility = if (bendOn || ditherOn) View.VISIBLE else View.GONE
        }

        val bend = Retro.button(this, "BEND", Retro.RED) {}
        bend.setOnClickListener {
            bendOn = !bendOn
            Retro.paint(bend, Retro.RED, bendOn)
            refreshKnobs()
        }
        Retro.paint(bend, Retro.RED, bendOn)
        val dither = Retro.button(this, "DITHER", Retro.GREEN) {}
        dither.setOnClickListener {
            ditherOn = !ditherOn
            Retro.paint(dither, Retro.GREEN, ditherOn)
            refreshKnobs()
        }
        Retro.paint(dither, Retro.GREEN, ditherOn)

        add(bend)
        add(dither)
        add(Retro.button(this, "PHOTO", Retro.AMBER) { if (feed.running) photoRequested.set(true) })
        add(Retro.button(this, "VIDEO", Retro.RED) { toggleRecording() }.also { videoButton = it })
        add(Retro.button(this, "FLIP", Retro.DIM) { feed.flip() })
        updateVideoButton()

        val label = Retro.label(this, "", Retro.AMBER, 11f).apply {
            val p = Retro.dp(this@MainActivity, 8f)
            setPadding(p, p, p, p)
            setOnClickListener { openLast() }
        }
        status = label

        val buttonsLp: FrameLayout.LayoutParams
        val knobsLp: FrameLayout.LayoutParams
        if (portrait) {
            val barH = Retro.dp(this, 64f)
            buttonsLp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, barH, Gravity.BOTTOM)
            knobsLp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
            knobsLp.bottomMargin = barH
        } else {
            buttonsLp = FrameLayout.LayoutParams(Retro.dp(this, 120f), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END)
            knobsLp = FrameLayout.LayoutParams(Retro.dp(this, 230f), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START)
        }
        val labelLp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
        for ((v, lp) in listOf(knobPanel to knobsLp, buttons to buttonsLp, label to labelLp)) {
            root.addView(v, lp)
            controls += v
        }
        refreshKnobs()
    }

    private fun percent(v: Float) = "${(v * 100).toInt()}%"

    private fun toggleRecording() {
        if (!feed.running) return
        wantRecording = !wantRecording
        updateVideoButton()
    }

    private fun updateVideoButton() {
        val b = videoButton ?: return
        b.text = if (wantRecording) "STOP" else "VIDEO"
        Retro.paint(b, Retro.RED, wantRecording)
    }

    private fun saved(uri: Uri?, mime: String, message: String) {
        if (uri == null) {
            Toast.makeText(this, "Could not save", Toast.LENGTH_SHORT).show()
            return
        }
        lastUri = uri
        lastMime = mime
        status?.text = "$message - TAP TO VIEW"
        if (mime.startsWith("image")) {
            val flash = View(this).apply { setBackgroundColor(0xFFFFFFFF.toInt()) }
            root.addView(flash, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            flash.animate().alpha(0f).setDuration(220).withEndAction { root.removeView(flash) }.start()
        }
    }

    private fun openLast() {
        val uri = lastUri ?: return
        startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, lastMime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }

    private companion object {
        const val TAG = "MainActivity"
    }
}
