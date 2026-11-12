package com.flipos.launcher.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import com.flipos.launcher.R
import com.flipos.launcher.data.LauncherPrefs
import com.flipos.launcher.util.accentColorAlpha
import com.flipos.launcher.util.applyFakeBold

/**
 * Home's own layout (clock, date, soft keys, D-pad pod) drawn over a
 * transparent background, for the wallpaper picker to lay over each
 * thumbnail. Uses [HomeScreenBinder], so it shows the user's real shortcuts,
 * and draws at the screen's width and the wallpapers' 3:4 shape.
 */
object HomePreview {

    /** Call from an Activity whose theme carries the user's accent (as Home's does). */
    fun renderOverlay(context: Context): Bitmap {
        val res = context.resources
        val width = res.displayMetrics.widthPixels
        val height = width * 4 / 3
        val root = LayoutInflater.from(context).inflate(R.layout.activity_main, FrameLayout(context), false)
        // Home sits under a transparent status bar; offscreen there are no insets, so pad for it.
        root.fitsSystemWindows = false
        root.setPadding(0, statusBarHeightPx(context), 0, 0)

        val prefs = LauncherPrefs(context)
        HomeScreenBinder.bindClock(context, root)
        HomeScreenBinder.bindLeftKey(context, root, prefs)
        HomeScreenBinder.bindRightKey(context, root, prefs)
        HomeScreenBinder.bindDpad(context, root, prefs)
        root.findViewById<ImageView>(R.id.softkey_center).apply {
            backgroundTintList = ColorStateList.valueOf(context.accentColorAlpha(0x4D))
            // Home focuses this button on arrival; selected draws the same tile.
            isSelected = true
        }
        root.applyFakeBold()

        root.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, width, height)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        return bitmap
    }

    private fun statusBarHeightPx(context: Context): Int {
        val res = context.resources
        val id = res.getIdentifier("status_bar_height", "dimen", "android")
        val px = if (id != 0) res.getDimensionPixelSize(id) else 0
        return if (px > 0) px else (24 * res.displayMetrics.density).toInt()
    }
}
