package com.flipos.launcher.util

import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import com.flipos.launcher.R

/**
 * Do Not Disturb on/off, for Home's `#` hold (the stock Kyocera Home's
 * shortcut). Changing it needs Do Not Disturb access (ACCESS_NOTIFICATION_POLICY
 * plus the system's access list, Android 6+) - a separate list from
 * Notification Access, which never binds on the E4810/E4811.
 */
object DoNotDisturb {

    fun isAccessGranted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return false
        return nm.isNotificationPolicyAccessGranted
    }

    /** Turns Do Not Disturb on (the phone's own priority rules) or off; asks for access first if needed. */
    fun toggle(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            Toast.makeText(context, R.string.toast_not_available, Toast.LENGTH_SHORT).show()
            return
        }
        if (!isAccessGranted(context)) {
            Toast.makeText(context, R.string.dnd_needs_access, Toast.LENGTH_SHORT).show()
            openAccessSettings(context)
            return
        }
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val turnOn = nm.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL ||
                nm.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_UNKNOWN
            nm.setInterruptionFilter(
                if (turnOn) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL,
            )
            Toast.makeText(context, if (turnOn) R.string.dnd_on else R.string.dnd_off, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, R.string.toast_not_available, Toast.LENGTH_SHORT).show()
        }
    }

    /** The phone's Do Not Disturb access list, where Allow brings up the phone's own confirmation. */
    fun openAccessSettings(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        context.openSettingsWithPath(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS, R.string.path_dnd_access)
    }
}
