package com.flipos.launcher.util

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * App-drawer entries for Kyocera's own Home menus (Media Center, Tools, Quick
 * Settings), which the stock Kyocera launcher opens but which have no
 * launcher icon of their own. Each is an `<activity-alias>` of
 * [com.flipos.launcher.activities.KyoceraShortcutActivity] whose `target`
 * meta-data names the Kyocera component to open (see AndroidManifest.xml).
 *
 * Component names were captured on the E4610; other Kyocera models may lack
 * some of them, in which case the trampoline just toasts "Not available".
 */
object KyoceraShortcuts {

    const val KYOCERA_HOME_PACKAGE = "jp.kyocera.kyocerahome"

    const val MEDIA_CENTER_ALIAS = "com.flipos.launcher.activities.MediaCenterShortcut"
    const val TOOLS_ALIAS = "com.flipos.launcher.activities.ToolsShortcut"
    const val QUICK_SETTINGS_ALIAS = "com.flipos.launcher.activities.QuickSettingsShortcut"

    /** Fully-qualified alias names, as they appear in ActivityInfo.name. */
    val ALIASES = setOf(MEDIA_CENTER_ALIAS, TOOLS_ALIAS, QUICK_SETTINGS_ALIAS)

    /**
     * Shows the aliases in the drawer only on phones that have Kyocera's Home
     * app, so other devices don't get three dead icons. Cheap and idempotent:
     * only writes a setting when the state actually changes.
     */
    fun syncEnabled(context: Context) {
        val pm = context.packageManager
        val present = try {
            pm.getPackageInfo(KYOCERA_HOME_PACKAGE, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
        val wanted = if (present) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        for (alias in ALIASES) {
            val component = ComponentName(context.packageName, alias)
            if (pm.getComponentEnabledSetting(component) != wanted) {
                pm.setComponentEnabledSetting(component, wanted, PackageManager.DONT_KILL_APP)
            }
        }
    }
}
