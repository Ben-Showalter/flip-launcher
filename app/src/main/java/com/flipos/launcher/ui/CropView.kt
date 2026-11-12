package com.flipos.launcher.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Shows [bitmap] filling the whole view, movable with [pan] and zoomable
 * with [zoomBy], for cropping a wallpaper by D-pad. The photo always covers
 * the view - it can't be moved or zoomed out far enough to show an edge -
 * so [visibleRect] is always a full, screen-shaped piece of it.
 */
class CropView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val drawMatrix = Matrix()

    /** Zoom on top of the scale that just covers the view; 1 = whole photo width or height. */
    private var zoom = 1f

    /** The photo point (in bitmap px) shown at the view's center. */
    private var centerX = 0f
    private var centerY = 0f

    var bitmap: Bitmap? = null
        set(value) {
            field = value
            reset()
        }

    /** Back to the whole photo, centered. */
    fun reset() {
        zoom = 1f
        bitmap?.let {
            centerX = it.width / 2f
            centerY = it.height / 2f
        }
        clampAndInvalidate()
    }

    /** Moves the photo so the view shows [dx], [dy] view px further right / down of it. */
    fun pan(dx: Float, dy: Float) {
        val scale = scale() ?: return
        centerX += dx / scale
        centerY += dy / scale
        clampAndInvalidate()
    }

    fun zoomBy(factor: Float) {
        zoom = (zoom * factor).coerceIn(1f, MAX_ZOOM)
        clampAndInvalidate()
    }

    /** The part of [bitmap] currently on screen, in bitmap px. */
    fun visibleRect(): Rect? {
        val bitmap = bitmap ?: return null
        val scale = scale() ?: return null
        val halfW = width / scale / 2f
        val halfH = height / scale / 2f
        return Rect(
            (centerX - halfW).roundToInt().coerceAtLeast(0),
            (centerY - halfH).roundToInt().coerceAtLeast(0),
            (centerX + halfW).roundToInt().coerceAtMost(bitmap.width),
            (centerY + halfH).roundToInt().coerceAtMost(bitmap.height),
        )
    }

    /** View px per bitmap px, or null before the photo or the layout is ready. */
    private fun scale(): Float? {
        val bitmap = bitmap ?: return null
        if (width == 0 || height == 0) return null
        return max(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height) * zoom
    }

    private fun clampAndInvalidate() {
        val bitmap = bitmap
        val scale = scale()
        if (bitmap != null && scale != null) {
            val halfW = width / scale / 2f
            val halfH = height / scale / 2f
            centerX = centerX.coerceIn(halfW, max(halfW, bitmap.width - halfW))
            centerY = centerY.coerceIn(halfH, max(halfH, bitmap.height - halfH))
        }
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        clampAndInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val bitmap = bitmap ?: return
        val scale = scale() ?: return
        drawMatrix.reset()
        drawMatrix.postTranslate(-centerX, -centerY)
        drawMatrix.postScale(scale, scale)
        drawMatrix.postTranslate(width / 2f, height / 2f)
        canvas.drawBitmap(bitmap, drawMatrix, paint)
    }

    companion object {
        const val MAX_ZOOM = 4f
    }
}
