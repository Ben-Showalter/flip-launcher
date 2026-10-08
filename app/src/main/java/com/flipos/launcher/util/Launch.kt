package com.flipos.launcher.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.annotation.StringRes
import com.flipos.launcher.R
import com.flipos.launcher.data.AppRepository

/** Launches an app by its stored [key], surfacing a toast if it can't be opened. */
fun Context.launchAppByKey(key: String) {
    val intent = AppRepository.launchIntentFor(key)
    if (intent == null) {
        Toast.makeText(this, R.string.toast_launch_unavailable, Toast.LENGTH_SHORT).show()
        return
    }
    try {
        startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(this, R.string.toast_launch_unavailable, Toast.LENGTH_SHORT).show()
    } catch (e: SecurityException) {
        Toast.makeText(this, R.string.toast_launch_denied, Toast.LENGTH_SHORT).show()
    }
}

/**
 * Opens the phone's own Settings app - preferring this hardware's Kyocera
 * Settings package (see [CategoryApps.systemSettingsPackage]; plain
 * ACTION_SETTINGS resolves ambiguously there), then the generic action.
 */
fun Context.openSystemSettings() {
    val preferred = CategoryApps.systemSettingsPackage(this)
        ?.let { packageManager.getLaunchIntentForPackage(it) }
    for (intent in listOfNotNull(preferred, Intent(Settings.ACTION_SETTINGS))) {
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (e: Exception) {
            // Try the next candidate.
        }
    }
    Toast.makeText(this, R.string.toast_not_available, Toast.LENGTH_SHORT).show()
}

/**
 * Opens a general system Settings list screen ([action]) and shows the manual
 * path to our entry in it ([manualPath]). OEM builds on keypad flip phones
 * don't honor deep links to a single app's page, and some lack a screen
 * outright - so never crash, and always tell the user where to go by D-pad.
 */
fun Context.openSettingsWithPath(action: String, @StringRes manualPath: Int) {
    try {
        startActivity(Intent(action))
        Toast.makeText(this, manualPath, Toast.LENGTH_LONG).show()
    } catch (e: Exception) {
        Toast.makeText(this, getString(R.string.toast_settings_manual_path, getString(manualPath)), Toast.LENGTH_LONG).show()
    }
}
