package com.flipos.launcher.util

import android.view.Window
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Hides the system navigation bar - on these flip phones that's the white
 * soft-key label bar the OS draws at the bottom, which duplicates (and
 * disagrees with) our own [com.flipos.launcher.ui.SoftKeyBar]. Sticky
 * immersive, so it stays hidden across key presses; the system T9 keyboard
 * may bring it back while typing, which is fine.
 *
 * Call from both onResume() and onWindowFocusChanged(true): dialogs and
 * other windows can restore the bar when they take focus.
 */
fun Window.hideNavigationBar() {
    WindowCompat.getInsetsController(this, decorView).apply {
        systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        hide(WindowInsetsCompat.Type.navigationBars())
    }
}
