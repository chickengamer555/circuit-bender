package com.chickengamer.circuitbender

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.BatteryManager
import android.os.PowerManager
import android.os.SystemClock
import android.os.Bundle
import android.util.Log
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.OrientationEventListener
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
import com.chickengamer.circuitbender.core.FramePacer
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
import kotlin.math.abs

/**
 * The screen is locked upright like a stock camera app; the phone's own
 * orientation decides whether a shot is portrait or landscape, the picture
 * turns to stay upright, and the round buttons turn in place.
 */
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
    @Volatile private var colours = 1f / 18 // 27 natural colours
    @Volatile private var pixel = Resolution.CAMCORDER.ordinal / (Resolution.entries.size - 1f)
    @Volatile private var contrast = 0.5f
    @Volatile private var brightness = 0.5f

    @Volatile private var battery = 3
    /** How the phone is held: 0, 90, 180 or 270 degrees clockwise from upright. */
    @Volatile private var deviceRotation = 0

    // --- Capture state ---------------------------------------------------------
    private var videoMode = false
    private val photoRequested = AtomicBoolean(false)
    @Volatile private var wantRecording = false
    @Volatile private var recordRotation = 0
    private var recorder: VideoRecorder? = null // camera thread only
    private var recordFrame: Bitmap? = null // camera thread only
    private var thumbFromRecording = false // camera thread only
    @Volatile private var recordStartedAt = 0L
    private var frame = 0L

    // What the last frame was sampled with, for mapping a tap onto the sensor.
    @Volatile private var lastSensor = intArrayOf(0, 0, 0, 3, 4) // w, h, rotation, aspectW, aspectH

    private val pipeline = Pipeline(seed = System.nanoTime())
    // Mostly a frame-rate cap: heat is handled by the thermal levels below,
    // so a cool phone keeps as smooth a picture as it can.
    private val pacer = FramePacer(maxFps = 30, maxBusy = 0.85f)
    private var sampleBuf: IntArray? = null // camera thread only

    /** The phone reports its temperature; the pacer slows down as it warms up. */
    private val thermalListener = PowerManager.OnThermalStatusChangedListener { status ->
        val was = pacer.thermal
        pacer.thermal = status
        if (status >= FramePacer.MODERATE && was < FramePacer.MODERATE) showReadout("Phone warm - slowing down")
    }
    private val io = Executors.newSingleThreadExecutor()

    private lateinit var feed: CameraFeed
    private lateinit var viewfinder: ViewfinderView
    private lateinit var root: FrameLayout
    private lateinit var shutter: ShutterView
    private lateinit var thumb: ImageView
    private lateinit var readout: TextView
    private lateinit var photoLabel: TextView
    private lateinit var videoLabel: TextView
    private val chips = mutableMapOf<Tab, TextView>()
    private val turning = mutableListOf<View>()
    private var sheet: View? = null
    private lateinit var topBar: View
    private lateinit var bottomPanel: View
    private var clean = false
    private var sheetTab = Tab.VHS
    private var torch = false
    private var lastUri: Uri? = null
    private var lastMime = "image/png"

    /** Videos record sound once allowed; until then they are silent. */
    private val askMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
    private var micAsked = false

    private fun micGranted() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun maybeAskMic() {
        if (micAsked || micGranted()) return
        micAsked = true
        askMic.launch(Manifest.permission.RECORD_AUDIO)
    }

    private val askCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            viewfinder.message = null
            feed.start()
        } else {
            viewfinder.message = "Camera permission needed - tap to retry"
        }
    }

    private val orientation by lazy {
        object : OrientationEventListener(this) {
            override fun onOrientationChanged(angle: Int) {
                if (angle == ORIENTATION_UNKNOWN) return
                // Snap to the nearest quarter turn, with a dead zone so it does
                // not flicker when the phone is held near 45 degrees.
                val nearest = ((angle + 45) / 90 % 4) * 90
                val off = abs(((angle - nearest + 540) % 360) - 180) // degrees from that quarter
                if (nearest != deviceRotation && off < 30) {
                    deviceRotation = nearest
                    runOnUiThread { turnButtons() }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // One thread is faster and cooler on phones; see Parallel.enabled.
        com.chickengamer.circuitbender.core.ParallelSwitch.set(false)
        if (intent.getBooleanExtra("bench", false)) {
            Thread {
                Benchmark.run(this)
                finish()
            }.start()
            return
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        viewfinder = ViewfinderView(this)
        root.addView(viewfinder, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)

        feed = CameraFeed(this, { 0 }, { msg -> runOnUiThread { viewfinder.message = msg } }, ::onFrame)
        buildControls()
        installGestures()
        if (granted()) feed.start() else askCamera.launch(Manifest.permission.CAMERA)
    }

    override fun onResume() {
        super.onResume()
        if (!::feed.isInitialized) return
        if (orientation.canDetectOrientation()) orientation.enable()
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        pacer.thermal = power.currentThermalStatus
        power.addThermalStatusListener(thermalListener)
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
        if (!::feed.isInitialized) return
        orientation.disable()
        (getSystemService(Context.POWER_SERVICE) as PowerManager).removeThermalStatusListener(thermalListener)
        // The camera stops delivering frames while paused: close the file now.
        if (wantRecording) {
            wantRecording = false
            feed.post { finishRecording() }
            shutter.recording = false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!::feed.isInitialized) return
        feed.shutdown()
        io.shutdown()
    }

    private fun granted() = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    // --- Camera thread -------------------------------------------------------------

    private fun <T> pick(options: List<T>, k: Float): T = options[(k.coerceIn(0f, 1f) * (options.size - 1) + 0.5f).toInt()]

    private fun settings(rotation: Int) = PipelineSettings(
        resolution = Knobs.pixel(pixel),
        portrait = rotation % 180 == 0,
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

    private fun onFrame(planes: YuvPlanes, sensorRotation: Int) {
        // Skip frames the pacer says to, unless a photo is waiting.
        val start = SystemClock.uptimeMillis()
        if (!photoRequested.get() && !pacer.shouldProcess(start)) return
        render(planes, sensorRotation)
        pacer.finished(start, SystemClock.uptimeMillis() - start)
    }

    private fun render(planes: YuvPlanes, sensorRotation: Int) {
        if (wantRecording && recorder == null) recordRotation = deviceRotation
        // A recording keeps the orientation it started with.
        val held = if (wantRecording || recorder != null) recordRotation else deviceRotation
        val s = settings(held)
        val w = s.width
        val h = s.height
        val front = feed.lensFacing == CameraSelector.LENS_FACING_FRONT
        val rot = CameraSampler.sampleRotation(sensorRotation, held, front)
        lastSensor = intArrayOf(planes.width, planes.height, rot, s.displayWidth, s.displayHeight)
        val px = CameraSampler.sample(planes, rot, w, h, s.displayWidth, s.displayHeight, sampleBuf)
        sampleBuf = px
        if (front) for (y in 0 until h) px.reverse(y * w, y * w + w)

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
        viewfinder.post(shown, w, h, s.displayWidth, s.displayHeight, smooth, held)

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
            recorder = VideoRecorder(vw, vh, File(cacheDir, "rec_${System.nanoTime()}.mp4"), withAudio = micGranted())
            recordStartedAt = System.currentTimeMillis()
            thumbFromRecording = true
        } catch (e: Exception) {
            // MediaCodec reports failures as several unrelated exception types.
            Log.e(TAG, "cannot start recording", e)
            wantRecording = false
            runOnUiThread {
                shutter.recording = false
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

    // --- Layout ------------------------------------------------------------------

    private fun iconButton(icon: Int, onClick: () -> Unit) = ImageView(this).apply {
        setImageResource(icon)
        scaleType = ImageView.ScaleType.CENTER
        background = Retro.rounded(this@MainActivity, 0x33FFFFFF, 24f)
        setOnClickListener { onClick() }
        turning += this
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildControls() {
        val icon = Retro.dp(this, 44f)

        // Top bar: light, readout, adjust.
        val top = FrameLayout(this).apply {
            val p = Retro.dp(this@MainActivity, 12f)
            setPadding(p, p, p, p)
        }
        val light = iconButton(R.drawable.ic_flash) {}
        light.setOnClickListener {
            torch = !torch
            feed.setTorch(torch)
            light.background = Retro.rounded(this, if (torch) Retro.ACCENT else 0x33FFFFFF, 24f)
        }
        top.addView(light, FrameLayout.LayoutParams(icon, icon, Gravity.START or Gravity.CENTER_VERTICAL))
        readout = Retro.text(this, "", 14f).apply {
            background = Retro.rounded(this@MainActivity, 0x99000000.toInt(), 14f)
            val p = Retro.dp(this@MainActivity, 6f)
            setPadding(p * 2, p, p * 2, p)
            alpha = 0f
            turning += this
        }
        top.addView(readout, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        top.addView(iconButton(R.drawable.ic_tune) { toggleSheet() }, FrameLayout.LayoutParams(icon, icon, Gravity.END or Gravity.CENTER_VERTICAL))
        top.addView(
            iconButton(R.drawable.ic_fullscreen) { setClean(true) },
            FrameLayout.LayoutParams(icon, icon, Gravity.END or Gravity.CENTER_VERTICAL).apply {
                rightMargin = icon + Retro.dp(this@MainActivity, 12f)
            },
        )
        topBar = top
        root.addView(top, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        // Bottom panel: effect chips, PHOTO / VIDEO, then thumbnail, shutter, flip.
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val p = Retro.dp(this@MainActivity, 12f)
            setPadding(p, p, p, p * 2)
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xEE000000.toInt(), 0x00000000))
        }
        val chipRow = LinearLayout(this).apply { gravity = Gravity.CENTER }
        for (tab in Tab.entries) {
            val chip = Retro.chip(this, tab.title) {}
            chip.setOnClickListener {
                setOn(tab, !isOn(tab))
                paintChips()
                if (sheet != null) openSheet(sheetTab)
            }
            chip.setOnLongClickListener {
                openSheet(tab)
                true
            }
            chips[tab] = chip
            chipRow.addView(chip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                val m = Retro.dp(this@MainActivity, 5f)
                setMargins(m, 0, m, 0)
            })
        }
        paintChips()
        bottom.addView(chipRow)

        photoLabel = Retro.text(this, "PHOTO", 13f)
        videoLabel = Retro.text(this, "VIDEO", 13f)
        val modeRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            val p = Retro.dp(this@MainActivity, 12f)
            photoLabel.setPadding(p, p, p, p)
            videoLabel.setPadding(p, p, p, p)
            addView(photoLabel)
            addView(videoLabel)
        }
        photoLabel.setOnClickListener { setVideoMode(false) }
        videoLabel.setOnClickListener { setVideoMode(true) }
        bottom.addView(modeRow)

        shutter = ShutterView(this)
        installShutter()
        thumb = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = Retro.rounded(this@MainActivity, 0x33FFFFFF, 10f)
            clipToOutline = true
            setOnClickListener { openLast() }
            turning += this
        }
        val flip = iconButton(R.drawable.ic_flip) { feed.flip() }
        val big = Retro.dp(this, 78f)
        val gap = Retro.dp(this, 34f)
        val captureRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            addView(thumb, LinearLayout.LayoutParams(Retro.dp(this@MainActivity, 50f), Retro.dp(this@MainActivity, 50f)))
            addView(shutter, LinearLayout.LayoutParams(big, big).apply { setMargins(gap, 0, gap, 0) })
            addView(flip, LinearLayout.LayoutParams(Retro.dp(this@MainActivity, 50f), Retro.dp(this@MainActivity, 50f)))
        }
        bottom.addView(captureRow)
        root.addView(bottom, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        bottomPanel = bottom
        paintMode()

        // Keep the picture in the space between the bars.
        val fit = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (!clean) viewfinder.setPictureArea(top.height, bottom.height)
        }
        top.addOnLayoutChangeListener(fit)
        bottom.addOnLayoutChangeListener(fit)
    }

    /** Turn the round buttons and readout so they read upright however the phone is held. */
    private fun turnButtons() {
        val target = -deviceRotation.toFloat()
        for (v in turning) {
            // Take the short way round (e.g. 0 -> -270 should turn +90).
            var from = v.rotation % 360
            var to = target
            while (to - from > 180) to -= 360
            while (from - to > 180) to += 360
            v.animate().rotation(to).setDuration(200).start()
        }
    }

    /**
     * Clean view: just the picture, full screen, no buttons - for sharing the
     * screen in a call or capturing it into OBS. Tap or press back to leave.
     */
    private fun setClean(on: Boolean) {
        clean = on
        closeSheet()
        val vis = if (on) View.GONE else View.VISIBLE
        topBar.visibility = vis
        bottomPanel.visibility = vis
        if (on) {
            viewfinder.setPictureArea(0, 0)
            Toast.makeText(this, "Tap to show controls", Toast.LENGTH_SHORT).show()
        } else {
            viewfinder.setPictureArea(topBar.height, bottomPanel.height)
        }
    }

    @Deprecated("Back button: leave clean view before leaving the app")
    override fun onBackPressed() {
        if (clean) setClean(false) else super.onBackPressed()
    }

    private fun paintChips() {
        for ((tab, chip) in chips) Retro.paintChip(chip, isOn(tab))
    }

    private fun paintMode() {
        photoLabel.setTextColor(if (videoMode) Retro.MUTED else Retro.ACCENT)
        videoLabel.setTextColor(if (videoMode) Retro.ACCENT else Retro.MUTED)
        shutter.video = videoMode
    }

    /** Switch PHOTO / VIDEO - not while a recording is running. */
    private fun setVideoMode(video: Boolean) {
        if (wantRecording) return
        videoMode = video
        if (video) maybeAskMic()
        paintMode()
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

    private fun showReadout(text: String) {
        readout.text = text
        readout.animate().cancel()
        readout.alpha = 1f
        readout.animate().alpha(0f).setStartDelay(900).setDuration(400).start()
    }

    // --- Shooting -------------------------------------------------------------------

    private fun shoot() {
        if (!feed.running) return
        if (!videoMode) {
            photoRequested.set(true)
            return
        }
        setRecording(!wantRecording)
    }

    private fun setRecording(on: Boolean) {
        wantRecording = on
        shutter.recording = on
    }

    /** Tap to shoot; hold to record a clip for as long as the finger stays down. */
    @SuppressLint("ClickableViewAccessibility")
    private fun installShutter() {
        var holding = false
        val hold = Runnable {
            // The permission dialog would steal the touch and end the clip at
            // once, so ask first and record on the next hold.
            if (!micGranted() && !micAsked) {
                maybeAskMic()
            } else if (feed.running && !wantRecording) {
                holding = true
                shutter.video = true
                setRecording(true)
            }
        }
        shutter.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    holding = false
                    // Holding records a clip only from PHOTO mode; in VIDEO mode
                    // every press simply starts or stops, however long it lasts.
                    if (!videoMode) v.postDelayed(hold, 500)
                }
                MotionEvent.ACTION_UP -> {
                    v.isPressed = false
                    v.removeCallbacks(hold)
                    if (holding) {
                        holding = false
                        setRecording(false)
                        paintMode()
                    } else {
                        shoot()
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    v.removeCallbacks(hold)
                    if (holding) {
                        holding = false
                        setRecording(false)
                        paintMode()
                    }
                }
            }
            true
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (event.repeatCount == 0) shoot()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // --- Gestures on the picture ------------------------------------------------------
    //   tap: focus there      double-tap: flip camera     pinch: zoom
    //   drag up/down: exposure     swipe left/right: VIDEO / PHOTO
    // Directions are as the user sees them, however the phone is held.

    /** A screen-space vector turned into the holder's frame of reference. */
    private fun held(dx: Float, dy: Float): Pair<Float, Float> = when (deviceRotation) {
        90 -> -dy to dx
        180 -> -dx to -dy
        270 -> dy to -dx
        else -> dx to dy
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun installGestures() {
        var exposureDrag = 0f
        val stepPx = Retro.dp(this, 36f).toFloat()
        val gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                exposureDrag = 0f
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (clean) {
                    setClean(false)
                    return true
                }
                if (sheet != null) {
                    closeSheet()
                    return true
                }
                if (!feed.running) {
                    if (!granted()) askCamera.launch(Manifest.permission.CAMERA)
                    return true
                }
                focusAt(e.x, e.y)
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                feed.flip()
                return true
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, sx: Float, sy: Float): Boolean {
                val (hx, hy) = held(sx, sy)
                if (abs(hy) <= abs(hx)) return false
                exposureDrag += hy // dragging up gives positive scroll distance
                val steps = (exposureDrag / stepPx).toInt()
                if (steps != 0) {
                    exposureDrag -= steps * stepPx
                    feed.exposureBy(steps)?.let { showReadout("EV %+.1f".format(it)) }
                }
                return true
            }

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                val (hx, hy) = held(vx, vy)
                if (abs(hx) < abs(hy) * 1.5f) return false
                setVideoMode(hx < 0) // swipe left for video, right for photo
                return true
            }
        })
        val pinch = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                feed.zoomBy(d.scaleFactor)?.let { showReadout("%.1fx".format(it)) }
                return true
            }
        })
        viewfinder.setOnTouchListener { _, e ->
            pinch.onTouchEvent(e)
            if (!pinch.isInProgress) gestures.onTouchEvent(e)
            true
        }
    }

    private fun focusAt(x: Float, y: Float) {
        val (u0, v) = viewfinder.toPicture(x, y) ?: return
        val (sw, sh, rot, aw, ah) = lastSensor.toList()
        if (sw == 0) return
        // The front camera's picture is mirrored after sampling.
        val u = if (feed.lensFacing == CameraSelector.LENS_FACING_FRONT) 1f - u0 else u0
        val (fx, fy) = CameraSampler.sensorPoint(u, v, rot, sw, sh, aw, ah)
        feed.focus(fx, fy)
        showFocusRing(x, y)
    }

    private fun showFocusRing(x: Float, y: Float) {
        val size = Retro.dp(this, 64f)
        val ring = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setStroke(Retro.dp(this@MainActivity, 2f), Retro.ACCENT)
            }
        }
        root.addView(ring, FrameLayout.LayoutParams(size, size).apply {
            leftMargin = (x - size / 2).toInt()
            topMargin = (y - size / 2).toInt()
        })
        ring.scaleX = 1.4f
        ring.scaleY = 1.4f
        ring.animate().scaleX(1f).scaleY(1f).setDuration(180).withEndAction {
            ring.animate().alpha(0f).setStartDelay(600).setDuration(300).withEndAction { root.removeView(ring) }.start()
        }.start()
    }

    // --- Adjust sheet: tap outside or the handle to close; effects stay as they are ---

    private fun toggleSheet() {
        if (sheet != null) closeSheet() else openSheet(sheetTab)
    }

    private fun openSheet(tab: Tab) {
        sheetTab = tab
        sheet?.let { root.removeView(it) }

        val scrim = FrameLayout(this).apply {
            setBackgroundColor(0x22000000)
            setOnClickListener { closeSheet() }
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Retro.rounded(this@MainActivity, Retro.GLASS, 22f)
            val p = Retro.dp(this@MainActivity, 18f)
            setPadding(p, Retro.dp(this@MainActivity, 8f), p, p)
            isClickable = true // taps inside the panel must not close it
        }
        val handle = View(this).apply {
            background = Retro.rounded(this@MainActivity, 0x66FFFFFF, 3f)
            setOnClickListener { closeSheet() }
        }
        panel.addView(handle, LinearLayout.LayoutParams(Retro.dp(this, 40f), Retro.dp(this, 5f)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = Retro.dp(this@MainActivity, 8f)
        })

        val tabs = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        for (t in Tab.entries) {
            tabs.addView(Retro.text(this, t.title, 16f, if (t == tab) Retro.ACCENT else Retro.MUTED).apply {
                val p = Retro.dp(this@MainActivity, 8f)
                setPadding(0, p, Retro.dp(this@MainActivity, 20f), p)
                setOnClickListener { openSheet(t) }
            })
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
            paintChips()
        }
        paintToggle()
        tabs.addView(toggle)
        panel.addView(tabs)

        val knobs = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun switch(labelOn: String, labelOff: String, get: () -> Boolean, set: (Boolean) -> Unit) {
            val chip = Retro.chip(this, "") {}
            fun paint() {
                chip.text = if (get()) labelOn else labelOff
                Retro.paintChip(chip, get())
            }
            chip.setOnClickListener {
                set(!get())
                paint()
            }
            paint()
            knobs.addView(chip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = Retro.dp(this@MainActivity, 10f)
            })
        }
        when (tab) {
            Tab.VHS -> {
                knobs.addView(Retro.slider(this, "Camera quality", quality, { pick(Quality.entries, it).let { q -> "${q.label} · ${q.samples}" } }) { quality = it })
                knobs.addView(Retro.slider(this, "Wear", wear, Retro::percent) { wear = it })
                knobs.addView(Retro.slider(this, "Colour bleed", bleed, Retro::percent) { bleed = it })
                knobs.addView(Retro.slider(this, "Softness", soft, Retro::percent) { soft = it })
                knobs.addView(Retro.slider(this, "Glow", glow, Retro::percent) { glow = it })
                switch("REC & date shown", "REC & date hidden", { overlayOn }) { overlayOn = it }
                switch("REC & date in photos", "REC & date not in photos", { overlayInPhotos }) { overlayInPhotos = it }
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
        val scroll = ScrollView(this).apply { addView(knobs) }
        panel.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val m = Retro.dp(this, 10f)
        scrim.addView(panel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
            setMargins(m, m, m, m)
        })
        // Never taller than about half the screen, so the picture stays in view.
        panel.post {
            val max = root.height / 2
            if (scroll.height > max) scroll.layoutParams = scroll.layoutParams.apply { height = max }
        }
        root.addView(scrim, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        sheet = scrim
    }

    private fun closeSheet() {
        sheet?.let { root.removeView(it) }
        sheet = null
    }

    // --- Results -----------------------------------------------------------------

    private fun setThumb(photo: Photo) {
        val full = Bitmap.createBitmap(photo.pixels, photo.width, photo.height, Bitmap.Config.ARGB_8888)
        val side = Retro.dp(this, 50f)
        val scale = side.toFloat() / minOf(photo.width, photo.height)
        val bmp = Bitmap.createScaledBitmap(full, maxOf(1, (photo.width * scale).toInt()), maxOf(1, (photo.height * scale).toInt()), true)
        if (bmp !== full) full.recycle()
        thumb.setImageBitmap(bmp)
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
            showReadout("Video saved")
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
