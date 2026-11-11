package com.flipos.launcher.util

import android.content.Context
import android.media.AudioManager
import android.widget.Toast
import com.flipos.launcher.R

/**
 * Ring <-> vibrate, for Home's `#` hold - what the stock Kyocera Home's `#`
 * hold does ("Phone set to Vibrate only", seen in logcat). Switching between
 * normal and vibrate needs no permission; only silent (Do Not Disturb) would.
 */
object VibrateMode {

    fun toggle(context: Context) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (am == null) {
            Toast.makeText(context, R.string.toast_not_available, Toast.LENGTH_SHORT).show()
            return
        }
        val toVibrate = am.ringerMode == AudioManager.RINGER_MODE_NORMAL
        try {
            am.ringerMode = if (toVibrate) AudioManager.RINGER_MODE_VIBRATE else AudioManager.RINGER_MODE_NORMAL
            Toast.makeText(context, if (toVibrate) R.string.vibrate_on else R.string.vibrate_off, Toast.LENGTH_SHORT).show()
        } catch (e: SecurityException) {
            // Leaving silent (Do Not Disturb) needs access this app doesn't ask for.
            Toast.makeText(context, R.string.toast_not_available, Toast.LENGTH_SHORT).show()
        }
    }
}
