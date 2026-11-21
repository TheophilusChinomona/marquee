package com.orca.iptv

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.TextView

/**
 * Shared Marquee visual language, now tuned to a Netflix-style 10-foot UI:
 * pure black ground, white type, white focus outlines, and a single red brand
 * accent. Artwork does the talking; chrome stays out of the way.
 */
object MarqueeUi {

    const val GROUND = 0xFF000000.toInt()
    const val BOOTH = 0xFF0A0A0A.toInt()
    const val VELVET = 0xFF141414.toInt()
    const val VELVET2 = 0xFF2A2A2A.toInt()
    const val INK = 0xFFFFFFFF.toInt()
    const val SAND = 0xFFB3B3B3.toInt()
    const val DUST = 0xFF808080.toInt()
    const val BULB = 0xFFE50914.toInt()
    const val BULB_DIM = 0xFF7A050A.toInt()
    const val AIR = 0xFFE50914.toInt()
    const val CURTAIN = 0xFFE50914.toInt()
    const val OLIVE = 0xFF46D369.toInt()
    const val LINE = 0x24FFFFFF
    const val LINE2 = 0x57FFFFFF

    fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    fun dp(context: Context, value: Float): Int =
        (value * context.resources.displayMetrics.density).toInt()

    fun display(): Typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)

    fun body(): Typeface = Typeface.create("sans-serif", Typeface.NORMAL)

    fun medium(): Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    fun text(
        context: Context,
        value: CharSequence,
        sizeSp: Float,
        color: Int,
        typeface: Typeface = body()
    ): TextView = TextView(context).apply {
        text = value
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(color)
        setTypeface(typeface)
        includeFontPadding = false
    }

    fun fill(context: Context, color: Int, radiusDp: Int, strokeDp: Int = 0, stroke: Int = Color.TRANSPARENT): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = dp(context, radiusDp).toFloat()
            if (strokeDp > 0) setStroke(dp(context, strokeDp), stroke)
        }

    fun focusSelector(
        context: Context,
        normalFill: Int,
        focusedFill: Int,
        radiusDp: Int,
        strokeDp: Int = 0,
        stroke: Int = Color.TRANSPARENT
    ): StateListDrawable = StateListDrawable().apply {
        addState(
            intArrayOf(android.R.attr.state_focused),
            fill(context, focusedFill, radiusDp, strokeDp, stroke)
        )
        addState(intArrayOf(), fill(context, normalFill, radiusDp, strokeDp, stroke))
    }

    fun textColors(normal: Int, focused: Int, selected: Int = normal): ColorStateList =
        ColorStateList(
            arrayOf(
                intArrayOf(android.R.attr.state_focused),
                intArrayOf(android.R.attr.state_selected),
                intArrayOf()
            ),
            intArrayOf(focused, selected, normal)
        )

    /**
     * Scale + shadow on focus. The caller owns the focus listener so it can add
     * its own state changes (ring, metadata, etc.).
     */
    fun scaleOnFocus(view: View, scale: Float = 1.06f) {
        view.animate()
            .scaleX(if (view.isFocused) scale else 1f)
            .scaleY(if (view.isFocused) scale else 1f)
            .setDuration(if (view.isFocused) 160L else 140L)
            .start()
        view.translationZ = if (view.isFocused) dp(view.context, 12).toFloat() else 0f
    }

    fun button(context: Context, label: String, primary: Boolean = false): TextView {
        val normalFill = if (primary) INK else 0xCC4D4D4D.toInt()
        val normalText = if (primary) GROUND else INK
        val focusedFill = if (primary) 0xFFE6E6E6.toInt() else INK
        val focusedText = GROUND
        return text(
            context,
            label,
            18f,
            normalText,
            medium()
        ).apply {
            gravity = Gravity.CENTER
            isFocusable = true
            isFocusableInTouchMode = true
            setPadding(dp(context, 26), 0, dp(context, 26), 0)
            minHeight = dp(context, 44)
            minWidth = 0
            background = focusSelector(context, normalFill, focusedFill, 6)
            setTextColor(textColors(normalText, focusedText))
            setOnFocusChangeListener { v, _ -> scaleOnFocus(v, 1.04f) }
        }
    }

    fun badge(context: Context, label: String, live: Boolean = false, fill: Boolean = false): TextView =
        text(
            context,
            label,
            if (live) 14f else 13f,
            INK,
            if (live) medium() else body()
        ).apply {
            gravity = Gravity.CENTER
            setPadding(dp(context, 9), 0, dp(context, 9), 0)
            minHeight = dp(context, 24)
            if (live) {
                background = fill(context, AIR, 3)
            } else if (fill) {
                background = fill(context, VELVET2, 5)
            } else {
                background = fill(context, Color.TRANSPARENT, 5, 1, LINE2)
            }
        }

    fun progress(context: Context, value: Float): android.widget.ProgressBar =
        android.widget.ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progress = (value.coerceIn(0f, 1f) * 1000).toInt()
            progressTintList = ColorStateList.valueOf(BULB)
            progressBackgroundTintList = ColorStateList.valueOf(BULB_DIM)
            isFocusable = false
        }

    fun verticalGradient(colors: IntArray, orientation: GradientDrawable.Orientation): GradientDrawable =
        GradientDrawable(orientation, colors).apply {
            shape = GradientDrawable.RECTANGLE
        }
}
