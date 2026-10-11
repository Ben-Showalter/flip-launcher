package com.flipos.launcher.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
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
 *
 * On Android 9+ (E4810/E4811) Kyocera Home's Quick Settings menu can't open
 * Airplane mode, Bluetooth or Hotspot unless Kyocera Home is the default
 * launcher, so there the Quick Settings alias opens Kyocera Settings' own copy
 * of the menu first ([settingsQuickSettingsIntent]) - the one the phone's
 * programmable-key shortcut opens, which works under any launcher. The E4610
 * keeps Kyocera Home's menu, which works there.
 */
object KyoceraShortcuts {

    const val KYOCERA_HOME_PACKAGE = "jp.kyocera.kyocerahome"

    const val MEDIA_CENTER_ALIAS = "com.flipos.launcher.activities.MediaCenterShortcut"
    const val TOOLS_ALIAS = "com.flipos.launcher.activities.ToolsShortcut"
    const val QUICK_SETTINGS_ALIAS = "com.flipos.launcher.activities.QuickSettingsShortcut"

    /**
     * Kyocera Settings' Quick Settings menu, sent exactly as the phone's own
     * programmable-key shortcut sends it (captured from logcat on Android 9+).
     */
    fun settingsQuickSettingsIntent(): Intent =
        Intent("jp.kyocera.settings.programmablekey.action.SHORTCUTS").setComponent(
            ComponentName("jp.kyocera.settings.nfp", "jp.kyocera.settings.nfp.core.Settings\$QuickSettingsActivity"),
        )

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
