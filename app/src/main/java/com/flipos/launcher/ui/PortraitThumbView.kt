package com.flipos.launcher.ui

import android.content.Context
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatImageView

/**
 * An ImageView whose height follows its width at the screen's 3:4 portrait
 * shape (240x320), so a wallpaper thumbnail shows the whole wallpaper as it
 * will look on the phone, however wide the grid column is.
 */
class PortraitThumbView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : AppCompatImageView(context, attrs, defStyleAttr) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = width * 4 / 3
        setMeasuredDimension(width, height)
    }
}
