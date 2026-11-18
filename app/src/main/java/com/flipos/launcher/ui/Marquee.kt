package com.flipos.launcher.ui

import android.text.TextUtils
import android.widget.TextView

/**
 * Scrolls this one-line text sideways when it doesn't fit, instead of
 * cutting it off with "…" (240px screens cut a lot). Text that fits stays
 * still. Android only scrolls a marquee while the view is *selected*: with
 * [always] it's selected now (soft keys, the Home banner); otherwise the
 * caller selects it while its row is highlighted ([setMarqueeRunning]).
 */
fun TextView.useMarquee(always: Boolean) {
    setSingleLine(true)
    ellipsize = TextUtils.TruncateAt.MARQUEE
    marqueeRepeatLimit = -1 // forever
    if (always) isSelected = true
}

/** Starts or stops this marquee - a row's text scrolls only while the row is highlighted. */
fun TextView.setMarqueeRunning(running: Boolean) {
    isSelected = running
}
