package com.flipos.launcher.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatImageView

/**
 * An ImageView whose height follows its width at the screen's 3:4 portrait
 * shape (240x320), so a wallpaper thumbnail shows the whole wallpaper as it
 * will look on the phone, however wide the grid column is. With [overlay]
 * set, it also draws Home's dimming [scrim] and its clock and soft keys on
 * top (see [HomePreview]), scaled down from the overlay's full size.
 */
class PortraitThumbView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : AppCompatImageView(context, attrs, defStyleAttr) {

    private val overlayPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    var overlay: Bitmap? = null
        set(value) { field = value; invalidate() }

    var scrim: Drawable? = null
        set(value) { field = value; invalidate() }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = width * 4 / 3
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val overlay = overlay ?: return
        val save = canvas.save()
        canvas.scale(width.toFloat() / overlay.width, height.toFloat() / overlay.height)
        scrim?.let {
            it.setBounds(0, 0, overlay.width, overlay.height)
            it.draw(canvas)
        }
        canvas.drawBitmap(overlay, 0f, 0f, overlayPaint)
        canvas.restoreToCount(save)
    }
}
