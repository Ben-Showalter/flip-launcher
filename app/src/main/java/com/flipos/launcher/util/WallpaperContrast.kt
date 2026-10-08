package com.flipos.launcher.util

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import androidx.core.graphics.ColorUtils

/**
 * Wallpaper-aware dimming for the screens drawn over the wallpaper (Home and
 * the App Drawer), so the status bar, clock, labels and soft keys stay
 * readable over bright or busy wallpapers without needlessly darkening dark
 * ones. [brightness] measures the wallpaper once per wallpaper (cached by
 * wallpaper id where the platform has one); it does real work - call it off
 * the main thread.
 */
object WallpaperContrast {

    /** Used until a measurement is available, or when none is possible (e.g. a live wallpaper). */
    const val DEFAULT_BRIGHTNESS = 0.5f

    private const val SAMPLE_SIZE = 24

    @Volatile private var cachedId = Int.MIN_VALUE
    @Volatile private var cachedBrightness = DEFAULT_BRIGHTNESS

    /** The current wallpaper's brightness, 0 (black) to 1 (white). */
    fun brightness(context: Context): Float {
        val wm = WallpaperManager.getInstance(context)
        val id = if (Build.VERSION.SDK_INT >= 24) wm.getWallpaperId(WallpaperManager.FLAG_SYSTEM) else Int.MIN_VALUE
        if (id != Int.MIN_VALUE && id == cachedId) return cachedBrightness
        val measured = measure(wm) ?: DEFAULT_BRIGHTNESS
        cachedId = id
        cachedBrightness = measured
        return measured
    }

    private fun measure(wm: WallpaperManager): Float? = try {
        if (Build.VERSION.SDK_INT >= 27) {
            // getDrawable() needs a storage permission from API 27; the
            // platform's own color summary doesn't.
            wm.getWallpaperColors(WallpaperManager.FLAG_SYSTEM)?.let { colors ->
                val swatches = listOfNotNull(colors.primaryColor, colors.secondaryColor, colors.tertiaryColor)
                swatches.map { ColorUtils.calculateLuminance(it.toArgb()).toFloat() }.average().toFloat()
            }
        } else {
            wm.drawable?.let { averageLuminance(it) }
        }
    } catch (e: Exception) {
        null
    }

    private fun averageLuminance(drawable: Drawable): Float {
        val sample = Bitmap.createBitmap(SAMPLE_SIZE, SAMPLE_SIZE, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, SAMPLE_SIZE, SAMPLE_SIZE)
        drawable.draw(Canvas(sample))
        var total = 0.0
        for (y in 0 until SAMPLE_SIZE) {
            for (x in 0 until SAMPLE_SIZE) total += ColorUtils.calculateLuminance(sample.getPixel(x, y))
        }
        sample.recycle()
        return (total / (SAMPLE_SIZE * SAMPLE_SIZE)).toFloat()
    }

    /**
     * Home's window background: a top-to-bottom black gradient, heaviest
     * behind the status bar and soft keys, scaled by [brightness].
     */
    fun homeScrim(brightness: Float): Drawable = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(
            black(lerp(0.40f, 0.72f, brightness)),
            black(lerp(0.0f, 0.30f, brightness)),
            black(lerp(0.50f, 0.80f, brightness)),
        ),
    )

    /** The App Drawer's window background: a flat black overlay scaled by [brightness]. */
    fun drawerScrim(brightness: Float): Drawable = ColorDrawable(black(lerp(0.62f, 0.85f, brightness)))

    private fun black(alpha: Float): Int = Color.argb((alpha.coerceIn(0f, 1f) * 255).toInt(), 0, 0, 0)

    private fun lerp(from: Float, to: Float, t: Float): Float = from + (to - from) * t.coerceIn(0f, 1f)
}
