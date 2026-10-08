package com.flipos.launcher.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import androidx.annotation.ColorInt
import androidx.core.graphics.ColorUtils
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sign
import kotlin.math.sin

/**
 * Squircle (superellipse, n=4) highlight shown behind the focused/pressed/
 * selected app drawer icon. Drawn as a path rather than a rounded-rect shape
 * so the corners read as a continuous curve like the icon shapes themselves,
 * and is invisible outside those states so it behaves like the selector
 * drawable it replaces.
 *
 * With [ringWidthPx] > 0 it's a ring instead of a solid fill: a [ringWidthPx]
 * outline in the given color around a faint light backing, so nothing solid
 * sits behind the icon itself - a fill in the icon's own color washed out
 * icons drawn mostly in that color. [setColor] sets the ring's color then.
 */
class SquircleDrawable(@ColorInt color: Int = 0, private val ringWidthPx: Float = 0f) : Drawable() {

    private val ring = ringWidthPx > 0f

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        if (ring) {
            style = Paint.Style.STROKE
            strokeWidth = ringWidthPx
            this.color = readableOnDark(color)
        } else {
            style = Paint.Style.FILL
            this.color = color
        }
    }
    private val backingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        this.color = RING_BACKING
    }
    private val path = Path()
    private var visible = false

    fun setColor(@ColorInt color: Int) {
        val resolved = if (ring) readableOnDark(color) else color
        if (paint.color != resolved) {
            paint.color = resolved
            invalidateSelf()
        }
    }

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        buildPath(bounds)
    }

    private fun buildPath(bounds: Rect) {
        path.reset()
        if (bounds.isEmpty) return
        // A stroke straddles the path, so inset by half its width to keep the
        // whole ring inside the bounds.
        val inset = if (ring) ringWidthPx / 2f else 0f
        val cx = bounds.exactCenterX()
        val cy = bounds.exactCenterY()
        val rx = bounds.width() / 2f - inset
        val ry = bounds.height() / 2f - inset
        for (i in 0..SEGMENTS) {
            val t = (i.toFloat() / SEGMENTS) * (2 * Math.PI).toFloat()
            val x = cx + rx * sign(cos(t)) * abs(cos(t)).pow(HALF_EXPONENT)
            val y = cy + ry * sign(sin(t)) * abs(sin(t)).pow(HALF_EXPONENT)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
    }

    override fun isStateful() = true

    override fun onStateChange(state: IntArray): Boolean {
        val newVisible = state.contains(android.R.attr.state_pressed) ||
            state.contains(android.R.attr.state_focused) ||
            state.contains(android.R.attr.state_selected)
        if (newVisible == visible) return false
        visible = newVisible
        invalidateSelf()
        return true
    }

    override fun draw(canvas: Canvas) {
        if (!visible) return
        if (ring) canvas.drawPath(path, backingPaint)
        canvas.drawPath(path, paint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private companion object {
        const val SEGMENTS = 64
        const val HALF_EXPONENT = 0.5f

        /** Faint white wash inside the ring - lifts the icon off the wallpaper without tinting it. */
        const val RING_BACKING = 0x2EFFFFFF

        /** Below this luminance a ring color is too dark to see over the dimmed wallpaper. */
        const val MIN_RING_LUMINANCE = 0.3

        /** An opaque version of [color], lightened toward white until it reads over a dark background. */
        @ColorInt
        fun readableOnDark(@ColorInt color: Int): Int {
            var c = ColorUtils.setAlphaComponent(color, 0xFF)
            var step = 0
            while (ColorUtils.calculateLuminance(c) < MIN_RING_LUMINANCE && step < 5) {
                c = ColorUtils.blendARGB(c, Color.WHITE, 0.25f)
                step++
            }
            return c
        }
    }
}

private fun Float.pow(exp: Float): Float = Math.pow(this.toDouble(), exp.toDouble()).toFloat()
