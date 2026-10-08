package com.flipos.launcher.util

import android.content.Context
import android.view.KeyEvent
import android.widget.Toast
import com.flipos.launcher.R
import com.flipos.launcher.data.LauncherPrefs

/**
 * The Mic/Assistant key: [KeyEvent.KEYCODE_F4] while an app has focus, but
 * this raw keycode when nothing does - match both (Flip-DumbPhoneGuide §1).
 */
const val KEYCODE_ASSISTANT_RAW = 287

/** Whether [keyCode] is the Mic/Assistant key under either of its codes. */
fun isAssistantKey(keyCode: Int): Boolean =
    keyCode == KeyEvent.KEYCODE_F4 || keyCode == KEYCODE_ASSISTANT_RAW

/** `*` arrives as either keycode depending on the device; treat them as the same key. */
fun isStarKey(keyCode: Int): Boolean =
    keyCode == KeyEvent.KEYCODE_STAR || keyCode == KeyEvent.KEYCODE_NUMPAD_MULTIPLY

/**
 * Routes the soft keys the same way on every screen: Left runs the screen's
 * primary action, Right (or MENU, which some devices send for a soft key)
 * opens Options.
 *
 * Both halves of each press are consumed and the action runs on key UP, and
 * only for a press whose DOWN this window actually saw - so a stray UP left
 * over from the previous window (e.g. the press that just opened or closed
 * this screen) never fires anything, and our own UP never leaks into
 * whatever window the action brings up.
 */
class SoftKeyRouter(
    private val onPrimary: () -> Unit,
    private val onOptions: () -> Unit,
) {
    private val pressed = mutableSetOf<Int>()

    fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode !in SOFT_KEYS) return false
        if (event.repeatCount == 0) pressed.add(keyCode)
        return true
    }

    fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode !in SOFT_KEYS) return false
        if (!pressed.remove(keyCode) || event.isCanceled) return true
        if (keyCode == KeyEvent.KEYCODE_SOFT_LEFT) onPrimary() else onOptions()
        return true
    }

    /** Forgets in-flight presses whose UP may never arrive (focus lost to a dialog, flip closed). */
    fun reset() = pressed.clear()

    private companion object {
        val SOFT_KEYS = setOf(KeyEvent.KEYCODE_SOFT_LEFT, KeyEvent.KEYCODE_SOFT_RIGHT, KeyEvent.KEYCODE_MENU)
    }
}

/**
 * Shows the unrecognized-key diagnostic toast for a fresh press of a key this
 * app doesn't otherwise know about. Rugged phones remap buttons and the user
 * has no logcat, so this is the fastest way to learn a new phone's keys.
 */
fun Context.toastIfUnknownKey(keyCode: Int, event: KeyEvent) {
    if (event.repeatCount != 0 || keyCode in KNOWN_KEYS) return
    Toast.makeText(
        this,
        getString(R.string.unrecognized_key_toast, keyCode, event.scanCode),
        Toast.LENGTH_SHORT,
    ).show()
}

/** Keys that should never trigger the unrecognized-key diagnostic toast. */
private val KNOWN_KEYS: Set<Int> = buildSet {
    addAll(KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9)
    addAll(
        listOf(
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_VOLUME_MUTE,
            KeyEvent.KEYCODE_POWER,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_DEL,
            KeyEvent.KEYCODE_CALL,
            KeyEvent.KEYCODE_ENDCALL,
            KeyEvent.KEYCODE_SOFT_LEFT,
            KeyEvent.KEYCODE_SOFT_RIGHT,
            KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_STAR,
            KeyEvent.KEYCODE_NUMPAD_MULTIPLY,
            KeyEvent.KEYCODE_POUND,
            KeyEvent.KEYCODE_CAMERA,
            LauncherPrefs.KEYCODE_CAMERA_ALT,
            LauncherPrefs.KEYCODE_CAMERA_ALT2,
            KeyEvent.KEYCODE_F4,
            KEYCODE_ASSISTANT_RAW,
        ),
    )
}
