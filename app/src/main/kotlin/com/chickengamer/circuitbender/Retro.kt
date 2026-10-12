package com.chickengamer.circuitbender

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

/** The app's few widgets: pill chips, tabs and labelled sliders on dark glass. */
object Retro {
    const val ACCENT = 0xFFFFB000.toInt()
    const val TEXT = 0xFFF2F2F2.toInt()
    const val MUTED = 0xFF9A9A9A.toInt()
    const val GLASS = 0xE6141414.toInt()

    private val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    fun dp(ctx: Context, v: Float) = (v * ctx.resources.displayMetrics.density).toInt()

    fun text(ctx: Context, value: String, size: Float = 13f, color: Int = TEXT) = TextView(ctx).apply {
        text = value
        textSize = size
        typeface = medium
        setTextColor(color)
    }

    fun rounded(ctx: Context, fill: Int, radiusDp: Float, stroke: Int = Color.TRANSPARENT) = GradientDrawable().apply {
        cornerRadius = dp(ctx, radiusDp).toFloat()
        setColor(fill)
        if (stroke != Color.TRANSPARENT) setStroke(dp(ctx, 1.5f), stroke)
    }

    /** A pill that reads as on (filled accent) or off (outlined). */
    fun chip(ctx: Context, label: String, onClick: () -> Unit) = text(ctx, label, 13f).apply {
        gravity = Gravity.CENTER
        val h = dp(ctx, 14f)
        val v = dp(ctx, 7f)
        setPadding(h, v, h, v)
        setOnClickListener { onClick() }
    }

    fun paintChip(chip: TextView, on: Boolean) {
        val ctx = chip.context
        chip.background = if (on) rounded(ctx, ACCENT, 20f) else rounded(ctx, 0x66000000, 20f, 0x99FFFFFF.toInt())
        chip.setTextColor(if (on) Color.BLACK else TEXT)
    }

    /** A labelled 0..1 slider: name on the left, current value on the right. */
    fun slider(ctx: Context, title: String, initial: Float, format: (Float) -> String, onChange: (Float) -> Unit): LinearLayout {
        val steps = 1000
        val value = text(ctx, format(initial), 13f, ACCENT)
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(text(ctx, title, 13f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(value)
        }
        val bar = SeekBar(ctx).apply {
            max = steps
            progress = (initial * steps).toInt()
            progressTintList = ColorStateList.valueOf(ACCENT)
            thumbTintList = ColorStateList.valueOf(ACCENT)
            progressBackgroundTintList = ColorStateList.valueOf(0x55FFFFFF)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    val v = p / steps.toFloat()
                    value.text = format(v)
                    if (fromUser) onChange(v)
                }

                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(ctx, 4f)
            setPadding(p, dp(ctx, 8f), p, 0)
            addView(header)
            addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    fun percent(v: Float) = "${(v * 100 + 0.5f).toInt()}%"
}
