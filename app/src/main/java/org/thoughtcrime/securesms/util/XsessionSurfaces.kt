package org.thoughtcrime.securesms.util

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import network.loki.messenger.R
import org.session.libsession.utilities.getColorFromAttr
import kotlin.math.roundToInt

internal class XsessionSurfaces(context: Context) {
    private val density = context.resources.displayMetrics.density
    private val background = ColorUtils.setAlphaComponent(context.getColorFromAttr(R.attr.colorPrimary), 255)
    private val foreground = ColorUtils.setAlphaComponent(context.getColorFromAttr(android.R.attr.textColorPrimary), 255)
    private val accent = ColorUtils.setAlphaComponent(context.getAccentColor(), 255)

    private val surface = ColorUtils.blendARGB(background, foreground, .045f)
    private val line = ColorUtils.blendARGB(surface, foreground, .16f)
    private val iconTint = if (ColorUtils.calculateContrast(accent, surface) >= 3.0) accent else foreground

    fun editor(): Drawable = GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        intArrayOf(ColorUtils.blendARGB(surface, accent, .10f), surface, surface),
    ).apply {
        val radius = 30f * density
        cornerRadii = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
    }

    fun banner(): Drawable = GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        intArrayOf(ColorUtils.blendARGB(surface, accent, .12f), surface, surface),
    ).apply {
        cornerRadius = 25f * density
        setStroke(density.roundToInt().coerceAtLeast(1), ColorUtils.blendARGB(line, accent, .28f))
    }

    fun icon(): Drawable = shape(ColorUtils.blendARGB(surface, accent, .16f), 15f)
    fun iconColor(): Int = iconTint
    fun handle(): Drawable = shape(line, 6f)

    fun input(): Drawable = StateListDrawable().apply {
        val fill = ColorUtils.blendARGB(background, foreground, .025f)
        addState(intArrayOf(android.R.attr.state_focused), shape(fill, 17f, accent))
        addState(intArrayOf(), shape(fill, 17f, line))
    }

    fun primaryButton(button: TextView) {
        val onAccent = if (ColorUtils.calculateContrast(Color.BLACK, accent) >=
            ColorUtils.calculateContrast(Color.WHITE, accent)
        ) Color.BLACK else Color.WHITE
        val disabled = ColorUtils.blendARGB(surface, foreground, .11f)
        button.setTextColor(
            ColorStateList(
                arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
                intArrayOf(ColorUtils.blendARGB(surface, foreground, .54f), onAccent),
            )
        )
        button.backgroundTintList = null
        button.background = StateListDrawable().apply {
            addState(intArrayOf(-android.R.attr.state_enabled), shape(disabled, 15f))
            addState(intArrayOf(), RippleDrawable(
                ColorStateList.valueOf(ColorUtils.setAlphaComponent(onAccent, 45)),
                shape(accent, 15f),
                null,
            ))
        }
    }

    private fun shape(fill: Int, radius: Float, border: Int? = null) = GradientDrawable().apply {
        cornerRadius = radius * density
        setColor(fill)
        border?.let { setStroke(density.roundToInt().coerceAtLeast(1), it) }
    }
}
