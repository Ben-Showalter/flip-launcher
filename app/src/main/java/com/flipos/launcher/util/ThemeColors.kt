package com.flipos.launcher.util

import android.content.Context
import android.util.TypedValue
import androidx.annotation.AttrRes
import androidx.core.graphics.ColorUtils

/** Resolves a theme color attribute (e.g. [android.R.attr.colorAccent]) to an int. */
fun Context.themeColor(@AttrRes attr: Int): Int {
    val tv = TypedValue()
    theme.resolveAttribute(attr, tv, true)
    return tv.data
}

/** The current accent color for this context's (possibly overlaid) theme. */
fun Context.accentColor(): Int = themeColor(androidx.appcompat.R.attr.colorAccent)

/** The accent color at [alpha] (0..255), handy for subtle focus fills over wallpaper. */
fun Context.accentColorAlpha(alpha: Int): Int = ColorUtils.setAlphaComponent(accentColor(), alpha)
