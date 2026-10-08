package com.flipos.launcher.activities

import com.flipos.launcher.R

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast

/**
 * Invisible trampoline behind the Kyocera shortcut aliases (see
 * [com.flipos.launcher.util.KyoceraShortcuts]): opens the component named by
 * the launching alias's `target` meta-data, then finishes. A plain [Activity]
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
        try {
            if (target == null) throw IllegalStateException("no target")
            startActivity(
                Intent(Intent.ACTION_MAIN)
                    .setComponent(target)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (e: Exception) {
            // Missing on this model, or not exported to other apps.
            Toast.makeText(this, R.string.toast_not_available, Toast.LENGTH_SHORT).show()
        }
        finish()
    }

    private companion object {
        const val META_TARGET = "target"
    }
}
