package com.flipos.launcher.util

import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

/**
 * Bold doesn't render with these phones' stock font, so swap every
 * bold-styled [TextView] under (and including) this view to fake bold,
 * which the paint draws itself. Call once after inflating a layout.
 */
fun View.applyFakeBold() {
    if (this is TextView && typeface?.isBold == true) {
        // Drop the real bold request first so the two don't stack on fonts
        // that do have a bold face (the single-arg setTypeface leaves the
        // paint's fake-bold flag alone).
        typeface = Typeface.create(typeface, Typeface.NORMAL)
        paint.isFakeBoldText = true
    }
    if (this is ViewGroup) {
        for (i in 0 until childCount) getChildAt(i).applyFakeBold()
    }
}
