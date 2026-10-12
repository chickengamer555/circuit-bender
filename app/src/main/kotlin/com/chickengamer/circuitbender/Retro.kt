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
    const val DIM = 0xFF8A8A8A.toInt()
    const val PANEL = 0xB0101010.toInt()

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
        setPadding(p, p, p, p)
        background = frame(ctx, color, false)
        setOnClickListener { onClick() }
    }

    /** Light up a button as on or off. */
    fun paint(view: TextView, color: Int, on: Boolean) {
        view.background = frame(view.context, if (on) color else DIM, on)
        view.setTextColor(if (on) color else DIM)
    }

    /** A labelled 0..1 knob; [format] names the current value. */
    fun knob(ctx: Context, title: String, initial: Float, format: (Float) -> String, onChange: (Float) -> Unit): LinearLayout {
        val steps = 100
        val caption = label(ctx, "", AMBER, 12f)
        fun caption(v: Float) {
            caption.text = "$title  ${format(v)}"
        }
        val bar = SeekBar(ctx).apply {
            max = steps
            progress = (initial * steps).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    val v = p / steps.toFloat()
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
}
