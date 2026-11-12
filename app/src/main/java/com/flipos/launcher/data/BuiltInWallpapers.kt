package com.flipos.launcher.data

import android.content.Context
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat

/**
 * Wallpapers bundled with the launcher (res/drawable-nodpi), offered in the
 * Wallpaper Picker so there's always a curated set to choose from without
 * leaving the launcher.
 */
object BuiltInWallpapers {

    private val NAMES = listOf(
        "wallpaper_pack_01",
        "wallpaper_pack_02",
        "wallpaper_pack_03",
        "wallpaper_pack_04",
        "wallpaper_pack_05",
        "wallpaper_pack_06",
        "wallpaper_pack_07",
        "wallpaper_pack_08",
        "wallpaper_pack_09",
        "wallpaper_pack_10",
        "wallpaper_pack_11",
        "wallpaper_pack_12",
        "wallpaper_pack_13",
        "wallpaper_pack_14",
        "wallpaper_pack_15",
        "wallpaper_pack_16",
        "wallpaper_pack_17",
        "wallpaper_pack_18",
        "wallpaper_pack_19",
        "wallpaper_pack_20",
        "wallpaper_pack_21",
        "wallpaper_pack_22",
        "wallpaper_pack_23",
        "wallpaper_pack_24",
        "wallpaper_pack_25",
        "wallpaper_pack_26",
        "wallpaper_pack_27",
        "wallpaper_pack_28",
        "wallpaper_pack_29",
        "wallpaper_pack_30",
        "wallpaper_pack_31",
        "wallpaper_pack_32",
        "wallpaper_pack_33",
        "wallpaper_pack_34",
        "wallpaper_pack_35",
        "wallpaper_pack_36",
    )

    fun names(): List<String> = NAMES

    fun loadThumbnail(context: Context, name: String): Drawable? {
        val resId = context.resources.getIdentifier(name, "drawable", context.packageName)
        return if (resId == 0) null else ContextCompat.getDrawable(context, resId)
    }

    fun resId(context: Context, name: String): Int =
        context.resources.getIdentifier(name, "drawable", context.packageName)
}
