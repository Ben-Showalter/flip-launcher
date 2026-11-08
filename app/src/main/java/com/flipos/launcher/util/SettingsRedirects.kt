package com.flipos.launcher.util

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.flipos.launcher.activities.HomeKeysSettingsActivity
import com.flipos.launcher.activities.SettingsRedirectChooserActivity
import com.flipos.launcher.activities.WallpaperPickerActivity

/**
 * Phone Settings screens that have a launcher equivalent: when one of them
 * comes to the front (seen by NotificationAccessibilityService's window
 * events), the launcher's own screen opens on top of it. The phone's screen
 * stays underneath, so Back from ours lands there. Component names captured
 * from logcat on the E4810/E4811.
 */
object SettingsRedirects {

    private const val KYOCERA_SETTINGS = "jp.kyocera.settings.nfp"

    enum class Target { SHORTCUT_KEYS, WALLPAPER, NOTIFICATIONS }

    /**
     * Which launcher screen [packageName]/[className] (with window [title])
     * stands for, or null. Kyocera Settings reuses `core.SubSettings` for many
     * pages and shows its sound pickers as plain Dialog windows, so those are
     * told apart by their title.
     */
    fun match(packageName: String, className: String, title: String): Target? {
        val lowerTitle = title.lowercase()
        return when {
            packageName == "jp.kyocera.customizekey" &&
                className == "jp.kyocera.customizekey.settings.ProgrammableKeysSettingsActivity" -> Target.SHORTCUT_KEYS
            packageName == KYOCERA_SETTINGS && className == "$KYOCERA_SETTINGS.core.SubSettings" &&
                "wallpaper" in lowerTitle -> Target.WALLPAPER
            packageName == KYOCERA_SETTINGS &&
                (className == "$KYOCERA_SETTINGS.sound.PresetRingtonePickerActivity" || className == "android.app.Dialog") &&
                "notification" in lowerTitle -> Target.NOTIFICATIONS
            else -> null
        }
    }

    /**
     * Opens the launcher's screen for [target] from a service: via a
     * PendingIntent, since the OEM throttles a service's plain startActivity.
     * Notifications asks first ([SettingsRedirectChooserActivity]).
     */
    fun open(context: Context, target: Target) {
        val intent = when (target) {
            Target.SHORTCUT_KEYS -> Intent(context, HomeKeysSettingsActivity::class.java)
            Target.WALLPAPER -> Intent(context, WallpaperPickerActivity::class.java)
            Target.NOTIFICATIONS -> Intent(context, SettingsRedirectChooserActivity::class.java)
        }.addFlags(
            // A task of its own on top of the phone's Settings: the launcher's
            // task holds Home and the app list, so closing ours there showed
            // those instead of the phone's screen.
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK or
                Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS,
        )
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        try {
            PendingIntent.getActivity(context, target.ordinal, intent, flags).send()
        } catch (e: Exception) {
            // Nothing opened; the phone's own screen stays, as before.
        }
    }
}
