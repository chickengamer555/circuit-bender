package com.chickengamer.circuitbender

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.util.Log
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
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
import com.chickengamer.circuitbender.core.Photo
import com.chickengamer.circuitbender.core.Pipeline
import com.chickengamer.circuitbender.core.PipelineSettings
import com.chickengamer.circuitbender.core.Quality
import com.chickengamer.circuitbender.core.Resolution
import com.chickengamer.circuitbender.core.YuvPlanes
import java.io.File
import java.time.LocalDateTime
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : ComponentActivity() {
    private enum class Tab(val title: String) { VHS("VHS"), BEND("Bend"), DITHER("Dither") }

    // --- Effect settings (read by the camera thread) ---------------------------
    @Volatile private var vhsOn = true
    @Volatile private var overlayOn = true
    @Volatile private var overlayInPhotos = false
    @Volatile private var quality = 0.5f // VHS SP
    @Volatile private var wear = 0.25f
    @Volatile private var bleed = 0.45f
    @Volatile private var soft = 0.45f
    @Volatile private var glow = 0.5f

    @Volatile private var bendOn = false
    @Volatile private var noise = 0.3f
    @Volatile private var tear = 0.1f
    @Volatile private var colour = 0f
    @Volatile private var memory = 0f
    @Volatile private var roll = 0f
    @Volatile private var blocks = 0f

    @Volatile private var ditherOn = false
    @Volatile private var style = 1f / 11 // Bayer 4x4
    @Volatile private var colours = 1f / 13 // 27 natural colours
    @Volatile private var pixel = Resolution.CAMCORDER.ordinal / (Resolution.entries.size - 1f)
    @Volatile private var contrast = 0.5f
    @Volatile private var brightness = 0.5f

    @Volatile private var portrait = false
    @Volatile private var battery = 3

    // --- Capture state ---------------------------------------------------------
    private var videoMode = false
    private val photoRequested = AtomicBoolean(false)
    @Volatile private var wantRecording = false
    private var recorder: VideoRecorder? = null // camera thread only
    private var recordFrame: Bitmap? = null // camera thread only
    private var thumbFromRecording = false // camera thread only
    @Volatile private var recordStartedAt = 0L
    private var frame = 0L

    private val pipeline = Pipeline(seed = System.nanoTime())
    private val io = Executors.newSingleThreadExecutor()

    private lateinit var feed: CameraFeed
    private lateinit var viewfinder: ViewfinderView
    private lateinit var root: FrameLayout
    private val controls = mutableListOf<View>()
    private var shutter: ShutterView? = null
    private var thumb: ImageView? = null
    private var photoLabel: TextView? = null
    private var videoLabel: TextView? = null
    private var zoomLabel: TextView? = null
    private var thumbBitmap: Bitmap? = null
    private var sheet: View? = null
    private var sheetTab = Tab.VHS
    private var lastUri: Uri? = null
    private var lastMime = "image/png"

    private val askCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            viewfinder.message = null
            feed.start()
        } else {
            viewfinder.message = "Camera permission needed - tap to retry"
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
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        root.addView(viewfinder, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)
        portrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT

        feed = CameraFeed(this, ::displayRotation, { msg -> runOnUiThread { viewfinder.message = msg } }, ::onFrame)
        buildControls()
        viewfinder.setOnTouchListener { _, e ->
            pinch.onTouchEvent(e)
            if (!pinch.isInProgress) gestures.onTouchEvent(e)
            true
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
            shutter?.recording = false
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
        resolution = Knobs.pixel(pixel),
        portrait = portrait,
        vhsEnabled = vhsOn,
        vhs = Knobs.vhs(wear, bleed, soft, glow),
        bendEnabled = bendOn,
        bend = Knobs.bend(noise, tear, colour, memory, roll, blocks),
        ditherEnabled = ditherOn,
        algorithm = Knobs.ditherStyle(style),
        palette = Knobs.palette(colours),
        ditherContrast = Knobs.contrast(contrast),
        ditherBrightness = Knobs.brightness(brightness),
        quality = pick(Quality.entries, quality),
    )

    private fun <T> pick(options: List<T>, k: Float): T = options[(k.coerceIn(0f, 1f) * (options.size - 1) + 0.5f).toInt()]

    private fun onFrame(planes: YuvPlanes, rotation: Int) {
        val s = settings()
        val w = s.width
        val h = s.height
        val px = CameraSampler.sample(planes, rotation, w, h, s.displayWidth, s.displayHeight)
        if (feed.lensFacing == CameraSelector.LENS_FACING_FRONT) {
            for (y in 0 until h) px.reverse(y * w, y * w + w)
        }

        if (wantRecording && recorder == null) startRecording(s)
        if (!wantRecording && recorder != null) finishRecording()
        val recording = recorder != null
        val takePhoto = photoRequested.getAndSet(false)

        val now = System.currentTimeMillis()
        val osd = if (!overlayOn) {
            null
        } else {
            OsdState(
                recording = recording,
                blinkOn = (now / 500) % 2 == 0L,
                tapeSpeed = "SP",
                battery = battery,
                counterSeconds = if (recording) (now - recordStartedAt) / 1000 else 0,
                dateTime = LocalDateTime.now(),
            )
        }
        val shown = pipeline.process(px, s, osd, frame++, clean = takePhoto || recording, keepOverlay = overlayInPhotos)
        val smooth = !s.ditherEnabled
        viewfinder.post(shown, w, h, s.displayWidth, s.displayHeight, smooth)

        if (recording) {
            val clean = pipeline.capture(1)
            val bmp = recordFrame?.takeIf { it.width == clean.width && it.height == clean.height }
                ?: Bitmap.createBitmap(clean.width, clean.height, Bitmap.Config.ARGB_8888).also { recordFrame = it }
            bmp.setPixels(clean.pixels, 0, clean.width, 0, 0, clean.width, clean.height)
            recorder?.addFrame(bmp, s.displayWidth, s.displayHeight, smooth)
            if (thumbFromRecording) {
                thumbFromRecording = false
                val small = pipeline.capturePhoto(160)
                runOnUiThread { setThumb(small) }
            }
        }
        if (takePhoto) {
            val photo = pipeline.capturePhoto(1280)
            io.execute {
                val uri = PhotoSaver.save(this, photo)
                runOnUiThread {
                    saved(uri, "image/png")
                    if (uri != null) setThumb(photo)
                }
            }
        }
    }

    private fun startRecording(s: PipelineSettings) {
        val (vw, vh) = VideoRecorder.sizeFor(s.displayWidth, s.displayHeight)
        try {
            recorder = VideoRecorder(vw, vh, File(cacheDir, "rec_${System.nanoTime()}.mp4"))
            recordStartedAt = System.currentTimeMillis()
            thumbFromRecording = true
        } catch (e: Exception) {
            // MediaCodec reports failures as several unrelated exception types.
            Log.e(TAG, "cannot start recording", e)
            wantRecording = false
            runOnUiThread {
                shutter?.recording = false
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
            runOnUiThread { saved(uri, "video/mp4") }
        }
    }

    // --- Main controls ---------------------------------------------------------------

    /** Effect chips, PHOTO/VIDEO, shutter, flip and thumbnail: along the bottom upright, down the right sideways. */
    private fun buildControls() {
        controls.forEach { root.removeView(it) }
        controls.clear()
        sheet?.let { root.removeView(it) }
        sheet = null

        val chips = LinearLayout(this).apply {
            orientation = if (portrait) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        fun chipLp() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            val m = Retro.dp(this@MainActivity, 4f)
            setMargins(m, m, m, m)
        }
        for (tab in Tab.entries) {
            val chip = Retro.chip(this, tab.title) {}
            chip.setOnClickListener {
                setOn(tab, !isOn(tab))
                Retro.paintChip(chip, isOn(tab))
                refreshSheet()
            }
            Retro.paintChip(chip, isOn(tab))
            chips.addView(chip, chipLp())
        }
        val adjust = Retro.chip(this, "Adjust") { openSheet(sheetTab) }
        adjust.background = Retro.rounded(this, 0x33FFFFFF, 20f)
        chips.addView(adjust, chipLp())

        val photoLabel = Retro.text(this, "PHOTO", 12f)
        val videoLabel = Retro.text(this, "VIDEO", 12f)
        this.photoLabel = photoLabel
        this.videoLabel = videoLabel
        val mode = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            val gap = Retro.dp(this@MainActivity, 10f)
            photoLabel.setPadding(gap, gap, gap, gap)
            videoLabel.setPadding(gap, gap, gap, gap)
            addView(photoLabel)
            addView(videoLabel)
        }
        photoLabel.setOnClickListener { setVideoMode(false) }
        videoLabel.setOnClickListener { setVideoMode(true) }

        val shutterView = ShutterView(this).apply {
            recording = wantRecording
            setOnClickListener { shoot() }
        }
        shutter = shutterView
        paintMode()

        val thumbView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = Retro.rounded(this@MainActivity, 0x33FFFFFF, 8f)
            clipToOutline = true
            thumbBitmap?.let { setImageBitmap(it) }
            setOnClickListener { openLast() }
        }
        thumb = thumbView
        val flip = Retro.text(this, "↻", 24f).apply {
            gravity = Gravity.CENTER
            background = Retro.rounded(this@MainActivity, 0x33FFFFFF, 24f)
            setOnClickListener { feed.flip() }
        }

        val small = Retro.dp(this, 48f)
        val big = Retro.dp(this, 76f)
        val capture = LinearLayout(this).apply {
            orientation = if (portrait) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            val m = Retro.dp(this@MainActivity, 18f)
            addView(thumbView, LinearLayout.LayoutParams(small, small))
            addView(shutterView, LinearLayout.LayoutParams(big, big).apply {
                if (portrait) setMargins(m, 0, m, 0) else setMargins(0, m, 0, m)
            })
            addView(flip, LinearLayout.LayoutParams(small, small))
        }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(0x99000000.toInt())
            val p = Retro.dp(this@MainActivity, 10f)
            setPadding(p, p, p, p)
        }
        if (portrait) {
            bar.addView(chips)
            bar.addView(mode)
            bar.addView(capture)
            root.addView(bar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
            bar.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> fitViewfinder(bottom = v.height, right = 0) }
        } else {
            bar.addView(chips)
            bar.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
            bar.addView(mode)
            bar.addView(capture)
            bar.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
            root.addView(bar, FrameLayout.LayoutParams(Retro.dp(this, 150f), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END))
            bar.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> fitViewfinder(bottom = 0, right = v.width) }
        }
        controls += bar

        val zoom = Retro.text(this, "", 14f).apply {
            background = Retro.rounded(this@MainActivity, 0x99000000.toInt(), 14f)
            val p = Retro.dp(this@MainActivity, 8f)
            setPadding(p * 2, p, p * 2, p)
            alpha = 0f
        }
        zoomLabel = zoom
        root.addView(zoom, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = Retro.dp(this@MainActivity, 24f)
        })
        controls += zoom
    }

    private fun paintMode() {
        photoLabel?.setTextColor(if (videoMode) Retro.MUTED else Retro.ACCENT)
        videoLabel?.setTextColor(if (videoMode) Retro.ACCENT else Retro.MUTED)
        shutter?.video = videoMode
    }

    /** Switch PHOTO / VIDEO - not while a recording is running. */
    private fun setVideoMode(video: Boolean) {
        if (wantRecording) return
        videoMode = video
        paintMode()
    }

    // --- Camera shortcuts: pinch to zoom, double-tap to flip, swipe for photo/video,
    //     volume keys to shoot -----------------------------------------------------

    private val gestures by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true

            override fun onDoubleTap(e: MotionEvent): Boolean {
                feed.flip()
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (!feed.running && !granted()) askCamera.launch(Manifest.permission.CAMERA)
                return true
            }

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (kotlin.math.abs(vx) < kotlin.math.abs(vy) * 1.5f) return false
                setVideoMode(vx < 0) // swipe left for video, right for photo
                return true
            }
        })
    }

    private val pinch by lazy {
        ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                val ratio = feed.zoomBy(d.scaleFactor) ?: return true
                showZoom(ratio)
                return true
            }
        })
    }

    private fun showZoom(ratio: Float) {
        val label = zoomLabel ?: return
        label.text = "%.1fx".format(ratio)
        label.animate().cancel()
        label.alpha = 1f
        label.animate().alpha(0f).setStartDelay(900).setDuration(400).start()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (event.repeatCount == 0) shoot()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    /** Centre the picture in the space the control bar leaves free. */
    private fun fitViewfinder(bottom: Int, right: Int) {
        val lp = viewfinder.layoutParams as FrameLayout.LayoutParams
        if (lp.bottomMargin == bottom && lp.rightMargin == right) return
        lp.bottomMargin = bottom
        lp.rightMargin = right
        viewfinder.post { viewfinder.layoutParams = lp }
    }

    private fun isOn(tab: Tab) = when (tab) {
        Tab.VHS -> vhsOn
        Tab.BEND -> bendOn
        Tab.DITHER -> ditherOn
    }

    private fun setOn(tab: Tab, on: Boolean) {
        when (tab) {
            Tab.VHS -> vhsOn = on
            Tab.BEND -> bendOn = on
            Tab.DITHER -> ditherOn = on
        }
    }

    private fun shoot() {
        if (!feed.running) return
        if (!videoMode) {
            photoRequested.set(true)
            return
        }
        wantRecording = !wantRecording
        shutter?.recording = wantRecording
    }

    // --- Adjust sheet: closes on a tap outside; effects stay as they are ---------------

    private fun openSheet(tab: Tab) {
        sheetTab = tab
        sheet?.let { root.removeView(it) }

        val scrim = FrameLayout(this).apply {
            setBackgroundColor(0x33000000)
            setOnClickListener { closeSheet() }
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Retro.rounded(this@MainActivity, Retro.GLASS, 18f)
            val p = Retro.dp(this@MainActivity, 16f)
            setPadding(p, p, p, p)
            isClickable = true // taps inside the panel must not close it
        }

        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (t in Tab.entries) {
            val label = Retro.text(this, t.title, 15f, if (t == tab) Retro.ACCENT else Retro.MUTED).apply {
                val p = Retro.dp(this@MainActivity, 8f)
                setPadding(0, p, Retro.dp(this@MainActivity, 18f), p)
                setOnClickListener { openSheet(t) }
            }
            tabs.addView(label)
        }
        tabs.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        val toggle = Retro.chip(this, "") {}
        fun paintToggle() {
            toggle.text = if (isOn(tab)) "On" else "Off"
            Retro.paintChip(toggle, isOn(tab))
        }
        toggle.setOnClickListener {
            setOn(tab, !isOn(tab))
            paintToggle()
            buildControlsKeepingSheet()
        }
        paintToggle()
        tabs.addView(toggle)
        panel.addView(tabs)

        val knobs = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        when (tab) {
            Tab.VHS -> {
                knobs.addView(Retro.slider(this, "Wear", wear, Retro::percent) { wear = it })
                knobs.addView(Retro.slider(this, "Colour bleed", bleed, Retro::percent) { bleed = it })
                knobs.addView(Retro.slider(this, "Softness", soft, Retro::percent) { soft = it })
                knobs.addView(Retro.slider(this, "Camera quality", quality, { pick(Quality.entries, it).let { q -> "${q.label} (${q.samples})" } }) { quality = it })
                knobs.addView(Retro.slider(this, "Glow", glow, Retro::percent) { glow = it })
                val overlay = Retro.chip(this, "") {}
                fun paintOverlay() {
                    overlay.text = if (overlayOn) "REC & date: shown" else "REC & date: hidden"
                    Retro.paintChip(overlay, overlayOn)
                }
                overlay.setOnClickListener {
                    overlayOn = !overlayOn
                    paintOverlay()
                }
                paintOverlay()
                knobs.addView(overlay, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = Retro.dp(this@MainActivity, 12f)
                })
                val inPhotos = Retro.chip(this, "") {}
                fun paintInPhotos() {
                    inPhotos.text = if (overlayInPhotos) "In photos & video: yes" else "In photos & video: no"
                    Retro.paintChip(inPhotos, overlayInPhotos)
                }
                inPhotos.setOnClickListener {
                    overlayInPhotos = !overlayInPhotos
                    paintInPhotos()
                }
                paintInPhotos()
                knobs.addView(inPhotos, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = Retro.dp(this@MainActivity, 8f)
                })
            }
            Tab.BEND -> {
                knobs.addView(Retro.slider(this, "Noise", noise, Retro::percent) { noise = it })
                knobs.addView(Retro.slider(this, "Tear", tear, Retro::percent) { tear = it })
                knobs.addView(Retro.slider(this, "Colour", colour, Retro::percent) { colour = it })
                knobs.addView(Retro.slider(this, "Memory", memory, Retro::percent) { memory = it })
                knobs.addView(Retro.slider(this, "Roll", roll, Retro::percent) { roll = it })
                knobs.addView(Retro.slider(this, "Blocks", blocks, Retro::percent) { blocks = it })
            }
            Tab.DITHER -> {
                knobs.addView(Retro.slider(this, "Style", style, { Knobs.ditherStyle(it).label }) { style = it })
                knobs.addView(Retro.slider(this, "Colours", colours, { Knobs.palette(it).name }) { colours = it })
                knobs.addView(Retro.slider(this, "Pixel size", pixel, { Knobs.pixel(it).label }) { pixel = it })
                knobs.addView(Retro.slider(this, "Contrast", contrast, { "x%.2f".format(Knobs.contrast(it)) }) { contrast = it })
                knobs.addView(Retro.slider(this, "Brightness", brightness, { "%+d".format(Knobs.brightness(it).toInt()) }) { brightness = it })
            }
        }
        panel.addView(ScrollView(this).apply { addView(knobs) })

        val lp = if (portrait) {
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
                val m = Retro.dp(this@MainActivity, 10f)
                setMargins(m, m, m, m)
            }
        } else {
            FrameLayout.LayoutParams(Retro.dp(this, 360f), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START).apply {
                val m = Retro.dp(this@MainActivity, 10f)
                setMargins(m, m, m, m)
            }
        }
        scrim.addView(panel, lp)
        root.addView(scrim, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        sheet = scrim
    }

    private fun closeSheet() {
        sheet?.let { root.removeView(it) }
        sheet = null
    }

    private fun refreshSheet() {
        if (sheet != null) openSheet(sheetTab)
    }

    /** The chips mirror the sheet's on/off switch. */
    private fun buildControlsKeepingSheet() {
        val tab = sheetTab
        buildControls()
        openSheet(tab)
    }

    // --- Results -----------------------------------------------------------------

    private fun setThumb(photo: Photo) {
        val full = Bitmap.createBitmap(photo.pixels, photo.width, photo.height, Bitmap.Config.ARGB_8888)
        val side = Retro.dp(this, 48f)
        val scale = side.toFloat() / minOf(photo.width, photo.height)
        val bmp = Bitmap.createScaledBitmap(full, maxOf(1, (photo.width * scale).toInt()), maxOf(1, (photo.height * scale).toInt()), true)
        if (bmp !== full) full.recycle()
        thumbBitmap = bmp
        thumb?.setImageBitmap(bmp)
    }

    private fun saved(uri: Uri?, mime: String) {
        if (uri == null) {
            Toast.makeText(this, "Could not save", Toast.LENGTH_SHORT).show()
            return
        }
        lastUri = uri
        lastMime = mime
        if (mime.startsWith("image")) {
            val flash = View(this).apply { setBackgroundColor(Color.WHITE) }
            root.addView(flash, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            flash.animate().alpha(0f).setDuration(200).withEndAction { root.removeView(flash) }.start()
        } else {
            Toast.makeText(this, "Video saved", Toast.LENGTH_SHORT).show()
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
