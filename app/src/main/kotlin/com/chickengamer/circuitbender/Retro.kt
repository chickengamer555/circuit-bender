package com.chickengamer.circuitbender

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

/** Chunky monospace widgets in camcorder-button colours. */
object Retro {
    const val AMBER = 0xFFFFB000.toInt()
    const val RED = 0xFFFF3030.toInt()
    const val GREEN = 0xFF39FF14.toInt()
    const val CYAN = 0xFF00E5FF.toInt()
    const val DIM = 0xFF8A8A8A.toInt()
    const val PANEL = 0xD8101010.toInt()

    fun dp(ctx: Context, v: Float) = (v * ctx.resources.displayMetrics.density).toInt()

    fun label(ctx: Context, text: String, color: Int = DIM, size: Float = 11f) = TextView(ctx).apply {
        this.text = text
        typeface = Typeface.MONOSPACE
        setTextColor(color)
        textSize = size
    }

    fun frame(ctx: Context, color: Int, filled: Boolean) = GradientDrawable().apply {
        cornerRadius = dp(ctx, 4f).toFloat()
        setStroke(dp(ctx, 2f), color)
        setColor(if (filled) (color and 0x00FFFFFF) or 0x55000000 else 0x66000000)
    }

    fun button(ctx: Context, text: String, color: Int = AMBER, onClick: () -> Unit) = TextView(ctx).apply {
        this.text = text
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        setTextColor(color)
        textSize = 13f
        gravity = Gravity.CENTER
        val p = dp(ctx, 8f)
        setPadding(p, p / 2, p, p / 2)
        background = frame(ctx, color, false)
        setOnClickListener { onClick() }
    }

    /** A latching switch: lit when on. */
    fun toggle(ctx: Context, text: String, initial: Boolean, color: Int = AMBER, onChange: (Boolean) -> Unit): TextView {
        var on = initial
        val view = button(ctx, text, color) {}
        fun paint() {
            view.background = frame(ctx, if (on) color else DIM, on)
            view.setTextColor(if (on) color else DIM)
        }
        view.setOnClickListener {
            on = !on
            paint()
            onChange(on)
        }
        paint()
        return view
    }

    /** Tap to step forward, long-press to step back. */
    fun <T> cycle(
        ctx: Context,
        title: String,
        options: List<T>,
        initial: T,
        name: (T) -> String = { it.toString() },
        color: Int = CYAN,
        onChange: (T) -> Unit,
    ): TextView {
        var i = options.indexOf(initial).coerceAtLeast(0)
        val view = button(ctx, "", color) {}
        fun paint() {
            view.text = if (title.isEmpty()) name(options[i]) else "$title ${name(options[i])}"
        }
        view.setOnClickListener {
            i = (i + 1) % options.size
            paint()
            onChange(options[i])
        }
        view.setOnLongClickListener {
            i = (i - 1 + options.size) % options.size
            paint()
            onChange(options[i])
            true
        }
        paint()
        return view
    }

    /** A labelled pot. */
    fun knob(
        ctx: Context,
        title: String,
        min: Float,
        max: Float,
        initial: Float,
        format: (Float) -> String = { "%.2f".format(it) },
        onChange: (Float) -> Unit,
    ): LinearLayout {
        val steps = 1000
        val caption = label(ctx, "", AMBER, 11f)
        fun value(p: Int) = min + (max - min) * p / steps
        fun caption(v: Float) {
            caption.text = "$title ${format(v)}"
        }
        val bar = SeekBar(ctx).apply {
            this.max = steps
            progress = (((initial - min) / (max - min)) * steps).toInt().coerceIn(0, steps)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    val v = value(p)
                    caption(v)
                    if (fromUser) onChange(v)
                }

                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        caption(initial)
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(caption)
            addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    fun row(ctx: Context, vararg views: android.view.View) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        for (v in views) {
            addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                val m = dp(ctx, 3f)
                setMargins(m, m, m, m)
            })
        }
    }

    fun heading(ctx: Context, text: String) = label(ctx, text, RED, 12f).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        setPadding(0, dp(ctx, 10f), 0, dp(ctx, 2f))
    }

}
