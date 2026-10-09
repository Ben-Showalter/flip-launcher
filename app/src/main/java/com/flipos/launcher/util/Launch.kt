package com.flipos.launcher.util

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import com.flipos.launcher.R
import com.flipos.launcher.activities.SettingsActivity
import com.flipos.launcher.data.AppRepository
import com.flipos.launcher.data.LauncherPrefs

/**
 * Launches an app by its stored [key], surfacing a toast if it can't be
 * opened. The phone's own Settings app first asks Phone Settings vs Home
 * Screen Settings (see [showSettingsChooser]) unless the user turned that off.
 */
fun Context.launchAppByKey(key: String) {
    val activity = this as? Activity
    if (activity != null && isPhoneSettingsKey(key) && LauncherPrefs(this).isSettingsChooserEnabled()) {
        activity.showSettingsChooser(key)
        return
    }
    launchAppDirect(key)
}

private fun Context.isPhoneSettingsKey(key: String): Boolean {
    val pkg = ComponentName.unflattenFromString(key)?.packageName ?: return false
    return pkg == CategoryApps.systemSettingsPackage(this)
}

/**
 * "Open which Settings?" - the phone's Settings app (Wi-Fi, sound, display)
 * or this launcher's own settings. "Don't ask again" remembers Phone
 * Settings; Advanced turns the question back on.
 */
private fun Activity.showSettingsChooser(phoneSettingsKey: String) {
    AlertDialog.Builder(this)
        .setTitle(R.string.settings_chooser_title)
        .setItems(
            arrayOf(getString(R.string.settings_chooser_phone), getString(R.string.settings_chooser_home)),
        ) { _, which ->
            if (which == 0) {
                launchAppDirect(phoneSettingsKey)
            } else {
                startActivity(Intent(this, SettingsActivity::class.java))
            }
        }
        .setNeutralButton(R.string.settings_chooser_dont_ask) { _, _ ->
            LauncherPrefs(this).setSettingsChooserEnabled(false)
            launchAppDirect(phoneSettingsKey)
        }
        .show()
}

private fun Context.launchAppDirect(key: String) {
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
