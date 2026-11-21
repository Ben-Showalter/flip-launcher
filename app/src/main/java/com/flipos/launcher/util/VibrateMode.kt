package com.flipos.launcher.util

import android.content.Context
import android.media.AudioManager
import android.widget.Toast
import com.flipos.launcher.R
import com.flipos.launcher.data.LauncherPrefs

/**
 * Vibrate on/off, for Home's `#` hold - what the stock Kyocera Home's `#`
 * hold does (logcat: "Phone set to Vibrate only", then back to the earlier
 * mode). The first hold remembers the current ringer mode and switches to
 * vibrate; the next hold, while still on vibrate, puts that mode back (ring
 * comes back at its earlier volume). Normal <-> vibrate needs no permission;
 * entering or leaving silent (Do Not Disturb) can be refused.
 */
object VibrateMode {

    fun toggle(context: Context) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (am == null) {
            Toast.makeText(context, R.string.toast_not_available, Toast.LENGTH_SHORT).show()
            return
        }
        val prefs = LauncherPrefs(context)
        val current = am.ringerMode
        val target = if (current == AudioManager.RINGER_MODE_VIBRATE) {
            prefs.getRingerModeBeforeVibrate().takeIf { it == AudioManager.RINGER_MODE_NORMAL || it == AudioManager.RINGER_MODE_SILENT }
                ?: AudioManager.RINGER_MODE_NORMAL
        } else {
            AudioManager.RINGER_MODE_VIBRATE
        }
        try {
            am.ringerMode = target
        } catch (e: SecurityException) {
            // Silent (Do Not Disturb) needs access this app doesn't ask for: fall back to ring.
            if (target == AudioManager.RINGER_MODE_SILENT) {
                try {
                    am.ringerMode = AudioManager.RINGER_MODE_NORMAL
                } catch (e2: SecurityException) {
                    Toast.makeText(context, R.string.toast_not_available, Toast.LENGTH_SHORT).show()
                    return
                }
            } else {
                Toast.makeText(context, R.string.toast_not_available, Toast.LENGTH_SHORT).show()
                return
            }
        }
        if (target == AudioManager.RINGER_MODE_VIBRATE) prefs.setRingerModeBeforeVibrate(current)
        val message = when (am.ringerMode) {
            AudioManager.RINGER_MODE_VIBRATE -> R.string.vibrate_on
            AudioManager.RINGER_MODE_SILENT -> R.string.ringer_silent
            else -> R.string.vibrate_off
        }
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }
}
