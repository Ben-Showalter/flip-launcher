package com.flipos.launcher.activities

import com.flipos.launcher.R

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import com.flipos.launcher.util.KyoceraShortcuts

/**
 * Invisible trampoline behind the Kyocera shortcut aliases (see
 * [com.flipos.launcher.util.KyoceraShortcuts]): opens the component named by
 * the launching alias's `target` meta-data, then finishes. On Android 9+ the
 * Quick Settings alias first tries Kyocera Settings' own Quick Settings
 * ([KyoceraShortcuts.settingsQuickSettingsIntent]) instead. A plain [Activity]
 * (not AppCompat) so it can use the framework's Theme.NoDisplay.
 */
class KyoceraShortcutActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val target = try {
            @Suppress("DEPRECATION") // int-flags overload kept for minSdk 21 compatibility
            packageManager.getActivityInfo(componentName, PackageManager.GET_META_DATA)
                .metaData?.getString(META_TARGET)
                ?.let { ComponentName.unflattenFromString(it) }
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
        val candidates = listOfNotNull(
            KyoceraShortcuts.settingsQuickSettingsIntent().takeIf {
                componentName.className == KyoceraShortcuts.QUICK_SETTINGS_ALIAS &&
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
            },
            target?.let { Intent(Intent.ACTION_MAIN).setComponent(it) },
        )
        val started = candidates.any { intent ->
            try {
                startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            } catch (e: Exception) {
                // Missing on this model, or not exported to other apps.
                false
            }
        }
        if (!started) Toast.makeText(this, R.string.toast_not_available, Toast.LENGTH_SHORT).show()
        finish()
    }

    private companion object {
        const val META_TARGET = "target"
    }
}
