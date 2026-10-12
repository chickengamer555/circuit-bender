package com.chickengamer.circuitbender

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
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
import com.chickengamer.circuitbender.core.BayerPhase
import com.chickengamer.circuitbender.core.BendSettings
import com.chickengamer.circuitbender.core.BridgeMode
import com.chickengamer.circuitbender.core.CameraSampler
import com.chickengamer.circuitbender.core.DataLine
import com.chickengamer.circuitbender.core.DitherAlgorithm
import com.chickengamer.circuitbender.core.FieldError
import com.chickengamer.circuitbender.core.LineMode
import com.chickengamer.circuitbender.core.OsdState
import com.chickengamer.circuitbender.core.Palettes
import com.chickengamer.circuitbender.core.Pipeline
import com.chickengamer.circuitbender.core.PipelineSettings
import com.chickengamer.circuitbender.core.Resolution
import com.chickengamer.circuitbender.core.SccbGlitch
import com.chickengamer.circuitbender.core.VhsSettings
import java.time.LocalDateTime
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.log10
import kotlin.random.Random

class MainActivity : ComponentActivity() {
    // --- State shared with the camera thread ------------------------------------
    @Volatile private var settings = PipelineSettings()
    @Volatile private var tapeSpeed = "SP"
    @Volatile private var tracking = 0f
    @Volatile private var showDate = true
    @Volatile private var glitchHeld = false
    @Volatile private var contact: Pair<Float, Float>? = null
    @Volatile private var ldrOn = false
    @Volatile private var ldrResistance = 1f
    @Volatile private var battery = 3
    private val captureRequested = AtomicBoolean(false)
    private val startedAt = System.currentTimeMillis()
    private var frame = 0L

    private val pipeline = Pipeline(seed = System.nanoTime())
    private val io = Executors.newSingleThreadExecutor()

    private lateinit var feed: CameraFeed
    private lateinit var viewfinder: ViewfinderView
    private lateinit var root: FrameLayout
    private lateinit var lastPhoto: TextView
    private var lastUri: Uri? = null
    private var panel: View? = null

    private val askCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            viewfinder.message = null
            feed.start()
        } else {
            viewfinder.message = "NO CAMERA PERMISSION - TAP TO RETRY"
        }
    }

    /** Android 8-9 only: shared Pictures needs the storage permission at runtime. */
    private val askStorage = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            captureRequested.set(true)
        } else {
            Toast.makeText(this, "Storage permission is needed to save photos", Toast.LENGTH_LONG).show()
        }
    }

    private val lightListener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            // An LDR's resistance falls as light rises: map ~1..10,000 lux onto
            // the pot range, bright light = low resistance = harder bend.
            ldrResistance = (1f - log10(e.values[0] + 1f) / 4f).coerceIn(0f, 1f)
        }

        override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(id: Int) {}
        override fun onDisplayRemoved(id: Int) {}
        override fun onDisplayChanged(id: Int) {
            feed.setRotation(displayRotation())
        }
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
        root.addView(rightColumn(), FrameLayout.LayoutParams(Retro.dp(this, 132f), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END))
        root.addView(leftColumn(), FrameLayout.LayoutParams(Retro.dp(this, 112f), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START))
        setContentView(root)

        feed = CameraFeed(this, ::displayRotation, { msg -> runOnUiThread { viewfinder.message = msg } }, ::onFrame)
        viewfinder.setOnClickListener {
            if (!feed.running && !granted(Manifest.permission.CAMERA)) askCamera.launch(Manifest.permission.CAMERA)
        }
        if (granted(Manifest.permission.CAMERA)) {
            feed.start()
        } else {
            askCamera.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        feed.shutdown()
        io.shutdown()
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun displayRotation(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        display?.rotation ?: Surface.ROTATION_0
    } else {
        @Suppress("DEPRECATION") // Activity.display is API 30+
        windowManager.defaultDisplay.rotation
    }

    private fun shoot() {
        if (!feed.running) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && !granted(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            askStorage.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        captureRequested.set(true)
    }

    override fun onResume() {
        super.onResume()
        val sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sm.getDefaultSensor(Sensor.TYPE_LIGHT)?.let { sm.registerListener(lightListener, it, SensorManager.SENSOR_DELAY_UI) }
        (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).registerDisplayListener(displayListener, null)
        // The phone may have been turned over while we were in the background.
        feed.setRotation(displayRotation())
        val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        battery = when {
            pct > 66 -> 3
            pct > 33 -> 2
            pct > 10 -> 1
            else -> 0
        }
    }

    override fun onPause() {
        super.onPause()
        (getSystemService(Context.SENSOR_SERVICE) as SensorManager).unregisterListener(lightListener)
        (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).unregisterDisplayListener(displayListener)
    }

    // --- Camera thread ------------------------------------------------------------

    private fun onFrame(planes: com.chickengamer.circuitbender.core.YuvPlanes, rotation: Int) {
        val s = effectiveSettings()
        val w = s.resolution.width
        val h = s.resolution.height
        val px = CameraSampler.sample(planes, rotation, w, h)
        if (feed.lensFacing == CameraSelector.LENS_FACING_FRONT) {
            for (y in 0 until h) px.reverse(y * w, y * w + w)
        }
        val now = System.currentTimeMillis()
        val osd = OsdState(
            recording = true,
            blinkOn = (now / 500) % 2 == 0L,
            tapeSpeed = tapeSpeed,
            battery = battery,
            counterSeconds = (now - startedAt) / 1000,
            dateTime = LocalDateTime.now(),
            showDate = showDate,
        )
        val out = pipeline.process(px, s, osd, frame++)
        viewfinder.post(out, w, h)
        if (captureRequested.getAndSet(false)) {
            val photo = pipeline.capture(maxOf(1, 1280 / w))
            io.execute {
                val uri = PhotoSaver.save(this, photo)
                runOnUiThread { onSaved(uri) }
            }
        }
    }

    /** The rack as set, plus whatever the performer's hands and the light are doing. */
    private fun effectiveSettings(): PipelineSettings {
        val s = settings
        var bend = s.bend
        val touch = contact
        val pot: Float? = when {
            touch != null -> touch.second
            ldrOn -> ldrResistance
            else -> null
        }
        if (pot != null) {
            var lines = bend.dataLines.map { if (it.mode != LineMode.OFF) it.copy(pot = pot) else it }
            // A bare finger on the contacts bridges the top data lines through skin.
            if (touch != null && lines.none { it.mode != LineMode.OFF }) {
                lines = lines.mapIndexed { i, l -> if (i >= 5) DataLine(LineMode.HIGH, pot) else l }
            }
            bend = bend.copy(dataLines = lines)
        }
        if (touch != null) bend = bend.copy(pixelClockPot = maxOf(bend.pixelClockPot, touch.first))
        val vhs = VhsSettings.forSpeed(tapeSpeed).copy(tracking = tracking)
        return s.copy(
            bendEnabled = s.bendEnabled || glitchHeld || touch != null || ldrOn,
            bend = bend,
            vhs = vhs,
        )
    }

    // --- UI -------------------------------------------------------------------------

    private fun update(f: (PipelineSettings) -> PipelineSettings) {
        settings = f(settings)
    }

    private fun updateBend(f: (BendSettings) -> BendSettings) = update { it.copy(bend = f(it.bend)) }

    private fun rightColumn(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val p = Retro.dp(this@MainActivity, 6f)
            setPadding(p, p, p, p)
        }
        fun add(v: View) = col.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = Retro.dp(this@MainActivity, 4f)
        })
        add(Retro.toggle(this, "BEND", settings.bendEnabled, Retro.RED) { on -> update { it.copy(bendEnabled = on) } })
        add(Retro.toggle(this, "DITHER", settings.ditherEnabled, Retro.GREEN) { on -> update { it.copy(ditherEnabled = on) } })
        add(Retro.toggle(this, "VHS", settings.vhsEnabled, Retro.CYAN) { on -> update { it.copy(vhsEnabled = on) } })
        add(Retro.cycle(this, "", Resolution.entries, settings.resolution, { it.label.substringBefore(' ') }) { r -> update { it.copy(resolution = r) } })
        add(Retro.cycle(this, "TAPE", listOf("SP", "LP", "EP"), tapeSpeed) { tapeSpeed = it })
        add(Retro.toggle(this, "LIGHT", false, Retro.AMBER) { on -> feed.setTorch(on) })
        add(Retro.button(this, "FLIP") { feed.flip() })

        val shutter = TextView(this).apply {
            text = "●"
            textSize = 44f
            gravity = Gravity.CENTER
            setTextColor(Retro.RED)
            background = Retro.frame(this@MainActivity, Retro.RED, false)
            setOnClickListener { shoot() }
        }
        col.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        col.addView(shutter, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Retro.dp(this, 84f)))
        lastPhoto = Retro.label(this, "", Retro.DIM, 10f).apply {
            gravity = Gravity.CENTER
            setOnClickListener { openLast() }
        }
        add(lastPhoto)
        return col
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun leftColumn(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = Retro.dp(this@MainActivity, 6f)
            setPadding(p, p, p, p)
        }
        fun add(v: View, height: Int = ViewGroup.LayoutParams.WRAP_CONTENT) =
            col.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height).apply {
                topMargin = Retro.dp(this@MainActivity, 4f)
            })
        add(Retro.button(this, "RACK", Retro.RED) { togglePanel { rackPanel() } })
        add(Retro.button(this, "FX", Retro.GREEN) { togglePanel { fxPanel() } })
        add(Retro.button(this, "CHAOS", Retro.AMBER) { randomize() })

        // Momentary push button: the bend is live only while held.
        val glitch = Retro.button(this, "GLITCH\n(hold)", Retro.RED) {}.apply {
            setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        glitchHeld = true
                        v.background = Retro.frame(context, Retro.RED, true)
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        glitchHeld = false
                        v.background = Retro.frame(context, Retro.RED, false)
                    }
                }
                true
            }
        }
        add(glitch, Retro.dp(this, 64f))

        // Body contacts: finger position is skin resistance across the bridge.
        val pad = TextView(this).apply {
            text = "BODY\nCONTACT"
            textSize = 10f
            typeface = android.graphics.Typeface.MONOSPACE
            gravity = Gravity.CENTER
            setTextColor(Retro.AMBER)
            background = Retro.frame(this@MainActivity, Retro.AMBER, false)
            setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                        contact = (e.x / v.width).coerceIn(0f, 1f) to (e.y / v.height).coerceIn(0f, 1f)
                        v.background = Retro.frame(context, Retro.AMBER, true)
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        contact = null
                        v.background = Retro.frame(context, Retro.AMBER, false)
                    }
                }
                true
            }
        }
        col.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        add(pad, Retro.dp(this, 110f))
        return col
    }

    private fun togglePanel(build: () -> View) {
        panel?.let {
            root.removeView(it)
            panel = null
            return
        }
        openPanel(build())
    }

    private fun openPanel(content: View) {
        panel?.let { root.removeView(it) }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Retro.PANEL)
            val p = Retro.dp(this@MainActivity, 10f)
            setPadding(p, p, p, p)
            addView(content)
        }
        val lp = FrameLayout.LayoutParams(Retro.dp(this, 330f), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START).apply {
            leftMargin = Retro.dp(this@MainActivity, 112f)
        }
        root.addView(scroll, lp)
        panel = scroll
    }

    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    /** The bend rack: every control is a real bend point (docs/RESEARCH.md 1.2). */
    private fun rackPanel(): View {
        val c = column()
        val b = settings.bend
        val ctx = this
        c.addView(Retro.heading(ctx, "SENSOR DATA BUS  D0-D7"))
        c.addView(Retro.label(ctx, "tie a line HI/LO; pot = series resistance (0 hard short, 1 open)"))
        for (bit in 7 downTo 0) {
            val line = b.dataLines[bit]
            val mode = Retro.cycle(ctx, "D$bit", LineMode.entries, line.mode, { if (it == LineMode.OFF) "--" else it.name }) { m ->
                updateBend { it.copy(dataLines = it.dataLines.toMutableList().also { l -> l[bit] = l[bit].copy(mode = m) }) }
            }
            val pot = Retro.knob(ctx, "pot", 0f, 1f, line.pot) { v ->
                updateBend { it.copy(dataLines = it.dataLines.toMutableList().also { l -> l[bit] = l[bit].copy(pot = v) }) }
            }
            c.addView(Retro.row(ctx, mode, pot))
        }

        val bits = (0..7).toList()
        c.addView(Retro.heading(ctx, "PATCH WIRES"))
        c.addView(
            Retro.row(
                ctx,
                Retro.cycle(ctx, "BRIDGE", BridgeMode.entries, b.bridge) { m -> updateBend { it.copy(bridge = m) } },
                Retro.cycle(ctx, "D", bits, b.bridgeA) { v -> updateBend { it.copy(bridgeA = v) } },
                Retro.cycle(ctx, "D", bits, b.bridgeB) { v -> updateBend { it.copy(bridgeB = v) } },
            ),
        )
        c.addView(
            Retro.row(
                ctx,
                Retro.label(ctx, "CROSS"),
                Retro.cycle(ctx, "D", bits, b.crossA) { v -> updateBend { it.copy(crossA = v) } },
                Retro.cycle(ctx, "D", bits, b.crossB) { v -> updateBend { it.copy(crossB = v) } },
            ),
        )
        c.addView(
            Retro.row(
                ctx,
                Retro.cycle(ctx, "XTALK", listOf(-1) + bits, b.crosstalkBit, { if (it < 0) "--" else "D$it" }) { v ->
                    updateBend { it.copy(crosstalkBit = v) }
                },
                Retro.toggle(ctx, "BYTE SLIP", b.bytePhaseSlip, Retro.CYAN) { on -> updateBend { it.copy(bytePhaseSlip = on) } },
            ),
        )
        c.addView(Retro.knob(ctx, "XTALK period 2^n", 0f, 6f, b.crosstalkPeriod.toFloat(), { it.toInt().toString() }) { v ->
            updateBend { it.copy(crosstalkPeriod = v.toInt()) }
        })

        c.addView(Retro.heading(ctx, "TIMING"))
        c.addView(Retro.knob(ctx, "PCLK pot (knee 0.5)", 0f, 1f, b.pixelClockPot) { v -> updateBend { it.copy(pixelClockPot = v) } })
        c.addView(Retro.knob(ctx, "HSYNC stride", -40f, 40f, b.strideOffset.toFloat(), { it.toInt().toString() }) { v ->
            updateBend { it.copy(strideOffset = v.toInt()) }
        })
        c.addView(Retro.knob(ctx, "VSYNC roll", -12f, 12f, b.vsyncRoll, { "%.1f".format(it) }) { v ->
            updateBend { it.copy(vsyncRoll = if (kotlin.math.abs(v) < 0.2f) 0f else v) }
        })

        c.addView(Retro.heading(ctx, "FRAME MEMORY"))
        c.addView(
            Retro.row(
                ctx,
                Retro.cycle(ctx, "ADDR", LineMode.entries, b.addressMode, { if (it == LineMode.OFF) "--" else it.name }) { m ->
                    updateBend { it.copy(addressMode = m) }
                },
                Retro.cycle(ctx, "A", (4..17).toList(), b.addressBit) { v -> updateBend { it.copy(addressBit = v) } },
            ),
        )

        c.addView(Retro.heading(ctx, "SENSOR / ISP"))
        c.addView(
            Retro.row(
                ctx,
                Retro.cycle(ctx, "BAYER", BayerPhase.entries, b.bayerPhase, {
                    when (it) {
                        BayerPhase.NONE -> "OK"
                        BayerPhase.SHIFT_X -> "+X"
                        BayerPhase.SHIFT_Y -> "+Y"
                        BayerPhase.SHIFT_XY -> "+XY"
                    }
                }) { v -> updateBend { it.copy(bayerPhase = v) } },
                Retro.cycle(ctx, "SCCB", SccbGlitch.entries, b.sccb, { if (it == SccbGlitch.NONE) "OK" else it.name.replace('_', ' ') }) { v ->
                    updateBend { it.copy(sccb = v) }
                },
            ),
        )
        c.addView(Retro.knob(ctx, "ADC gain", 0.2f, 4f, b.adcGain) { v -> updateBend { it.copy(adcGain = if (kotlin.math.abs(v - 1f) < 0.04f) 1f else v) } })

        c.addView(Retro.heading(ctx, "OUTPUT"))
        c.addView(Retro.knob(ctx, "JPEG bit errors", 0f, 1f, b.jpegErrors) { v -> updateBend { it.copy(jpegErrors = v) } })
        c.addView(Retro.cycle(ctx, "FIELD", FieldError.entries, b.fieldError, { if (it == FieldError.NONE) "OK" else it.name }) { v ->
            updateBend { it.copy(fieldError = v) }
        })
        c.addView(Retro.knob(ctx, "Brownout", 0f, 1f, b.brownout) { v -> updateBend { it.copy(brownout = v) } })

        c.addView(Retro.heading(ctx, "MODULATION"))
        c.addView(Retro.toggle(ctx, "LDR -> data pots (light sensor)", ldrOn, Retro.AMBER) { on -> ldrOn = on })
        c.addView(
            Retro.row(
                ctx,
                Retro.button(ctx, "RESET RACK", Retro.DIM) {
                    updateBend { BendSettings() }
                    openPanel(rackPanel())
                },
                Retro.button(ctx, "CHAOS", Retro.AMBER) {
                    randomize()
                    openPanel(rackPanel())
                },
            ),
        )
        return c
    }

    private fun fxPanel(): View {
        val c = column()
        val s = settings
        val ctx = this
        c.addView(Retro.heading(ctx, "DITHER"))
        c.addView(Retro.cycle(ctx, "", DitherAlgorithm.entries, s.algorithm, { it.label }, Retro.GREEN) { a -> update { it.copy(algorithm = a) } })
        c.addView(Retro.cycle(ctx, "", Palettes.ALL, s.palette, { it.name }, Retro.GREEN) { p -> update { it.copy(palette = p) } })
        c.addView(Retro.label(ctx, "Game Boy Camera = the real 4x4 x 3-threshold matrix, at 128x112 with a GB palette"))
        c.addView(Retro.button(ctx, "GAME BOY CAMERA PRESET", Retro.GREEN) {
            update {
                it.copy(
                    resolution = Resolution.GAME_BOY, ditherEnabled = true,
                    algorithm = DitherAlgorithm.GAME_BOY_CAMERA, palette = Palettes.GAME_BOY,
                )
            }
            openPanel(fxPanel())
        })

        c.addView(Retro.heading(ctx, "TAPE"))
        c.addView(Retro.knob(ctx, "Tracking error", 0f, 1f, tracking) { v -> tracking = v })
        c.addView(Retro.toggle(ctx, "DATE STAMP", showDate, Retro.CYAN) { on -> showDate = on })
        return c
    }

    private fun randomize() {
        val r = Random(System.nanoTime())
        var b = BendSettings()
        repeat(2 + r.nextInt(3)) {
            b = when (r.nextInt(12)) {
                0 -> b.copy(dataLines = b.dataLines.toMutableList().also { l ->
                    l[r.nextInt(8)] = DataLine(if (r.nextBoolean()) LineMode.HIGH else LineMode.LOW, r.nextFloat() * 0.7f)
                })
                1 -> b.copy(bridge = if (r.nextBoolean()) BridgeMode.AND else BridgeMode.OR, bridgeA = r.nextInt(8), bridgeB = r.nextInt(8))
                2 -> b.copy(crossA = r.nextInt(8), crossB = r.nextInt(8))
                3 -> b.copy(crosstalkBit = r.nextInt(8), crosstalkPeriod = r.nextInt(6))
                4 -> b.copy(bytePhaseSlip = true)
                5 -> b.copy(pixelClockPot = 0.5f + r.nextFloat() * 0.5f)
                6 -> b.copy(strideOffset = r.nextInt(-12, 13))
                7 -> b.copy(addressMode = if (r.nextBoolean()) LineMode.HIGH else LineMode.LOW, addressBit = 8 + r.nextInt(9))
                8 -> b.copy(bayerPhase = BayerPhase.entries[1 + r.nextInt(3)])
                9 -> b.copy(sccb = SccbGlitch.entries[1 + r.nextInt(4)])
                10 -> b.copy(jpegErrors = r.nextFloat())
                else -> b.copy(vsyncRoll = r.nextInt(-4, 5).toFloat())
            }
        }
        update { it.copy(bend = b, bendEnabled = true) }
    }

    private fun onSaved(uri: Uri?) {
        if (uri == null) {
            Toast.makeText(this, "Could not save photo", Toast.LENGTH_SHORT).show()
            return
        }
        lastUri = uri
        lastPhoto.text = "SAVED\ntap to view"
        // A quick white flash, like the viewfinder blanking on a still.
        val flash = View(this).apply { setBackgroundColor(0xFFFFFFFF.toInt()) }
        root.addView(flash, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        flash.animate().alpha(0f).setDuration(220).withEndAction { root.removeView(flash) }.start()
    }

    private fun openLast() {
        val uri = lastUri ?: return
        if (uri.scheme != "content") return
        startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "image/png").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }
}
