package com.flipos.launcher.data

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat

/**
 * Wallpapers bundled with the launcher (res/drawable-nodpi), offered in the
 * Wallpaper Picker so there's always a curated set to choose from without
 * leaving the launcher.
 */
object BuiltInWallpapers {

    private val NAMES = listOf(
        "wallpaper_home_01",
        "wallpaper_home_02",
        "wallpaper_home_03",
        "wallpaper_home_04",
        "wallpaper_home_05",
        "wallpaper_home_06",
        "wallpaper_home_07",
        "wallpaper_home_08",
        "wallpaper_home_09",
        "wallpaper_home_10",
        "wallpaper_home_11",
        "wallpaper_home_12",
        "wallpaper_home_13",
        "wallpaper_home_14",
        "wallpaper_home_15",
        "wallpaper_home_16",
        "wallpaper_home_17",
        "wallpaper_home_18",
        "wallpaper_home_19",
        "wallpaper_home_20",
        "wallpaper_home_21",
        "wallpaper_home_22",
        "wallpaper_home_23",
        "wallpaper_home_24",
        "wallpaper_home_25",
        "wallpaper_home_26",
        "wallpaper_home_27",
        "wallpaper_home_28",
        "wallpaper_home_29",
        "wallpaper_home_30",
        "wallpaper_home_31",
        "wallpaper_home_32",
        "wallpaper_home_33",
        "wallpaper_home_34",
        "wallpaper_home_35",
        "wallpaper_home_36",
        "wallpaper_home_37",
        "wallpaper_home_38",
        "wallpaper_home_39",
        "wallpaper_home_40",
        "wallpaper_home_41",
        "wallpaper_home_42",
        "wallpaper_home_43",
        "wallpaper_home_44",
        "wallpaper_home_45",
        "wallpaper_home_47",
        "wallpaper_home_48",
        "wallpaper_home_49",
        "wallpaper_home_50",
        "wallpaper_home_51",
        "wallpaper_home_52",
        "wallpaper_home_53",
        "wallpaper_home_54",
        "wallpaper_home_55",
        "wallpaper_home_56",
        "wallpaper_home_57",
    )

    fun names(): List<String> = NAMES

    fun loadThumbnail(context: Context, name: String): Drawable? {
        val resId = context.resources.getIdentifier(name, "drawable", context.packageName)
        return if (resId == 0) null else ContextCompat.getDrawable(context, resId)
    }

    fun resId(context: Context, name: String): Int =
        context.resources.getIdentifier(name, "drawable", context.packageName)

    /**
     * Sets [name] as the phone's wallpaper. Does real work (decode + the
     * system's own save) - call off the main thread. Throws if it can't.
     */
    fun apply(context: Context, name: String) {
        val appContext = context.applicationContext
        val resId = resId(appContext, name)
        require(resId != 0) { "No wallpaper $name" }
        val wm = WallpaperManager.getInstance(appContext)
        val bitmap = decodeSampled(appContext, resId, wm.desiredMinimumWidth.coerceAtLeast(1), wm.desiredMinimumHeight.coerceAtLeast(1))
        try {
            wm.setBitmap(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Decodes [resId] downsampled to roughly [reqWidth]x[reqHeight], so a
     * large bundled wallpaper doesn't allocate a full-resolution bitmap
     * (which can OOM on low-end devices).
     */
    private fun decodeSampled(context: Context, resId: Int, reqWidth: Int, reqHeight: Int): Bitmap {
        val res = context.resources
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeResource(res, resId, bounds)
        var sample = 1
        var halfW = bounds.outWidth / 2
        var halfH = bounds.outHeight / 2
        while (halfW >= reqWidth && halfH >= reqHeight) {
            sample *= 2
            halfW /= 2
            halfH /= 2
        }
        return BitmapFactory.decodeResource(res, resId, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}
