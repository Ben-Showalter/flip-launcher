package com.flipos.launcher.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.flipos.launcher.data.LauncherPrefs

/**
 * One-time default apps for Home's D-pad and outer buttons, for whichever
 * are still unassigned (assignments the user made are kept, and clearing one
 * later stays cleared): Up = Calculator, Left = Calendar, Right = Quick
 * Settings, Down = Notepad, SOS = Weather, PTT = Flashlight, outer END =
 * Timer, outer Speaker = Alarm clock. Each picks the first of its listed
 * packages that's installed; an app this phone lacks leaves the key unset.
 */
object DefaultKeys {

    fun seedOnce(context: Context) {
        val prefs = LauncherPrefs(context)
        if (prefs.isDefaultKeysSeeded()) return
        fun firstPackage(vararg packages: String): String? = packages.firstNotNullOfOrNull { mainKey(context, it) }

        if (prefs.getDpadUpApp() == null) {
            (firstPackage("com.kyocera.calculator2", "com.android.calculator2") ?: categoryKey(context, Intent.CATEGORY_APP_CALCULATOR))
                ?.let { prefs.setDpadUpApp(it) }
        }
        if (prefs.getDpadLeftApp() == null) {
            (CategoryApps.calendarPackage(context)?.let { mainKey(context, it) } ?: firstPackage("com.android.calendar"))
                ?.let { prefs.setDpadLeftApp(it) }
        }
        if (prefs.getDpadRightApp() == null && installed(context, KyoceraShortcuts.KYOCERA_HOME_PACKAGE)) {
            prefs.setDpadRightApp(ComponentName(context.packageName, KyoceraShortcuts.QUICK_SETTINGS_ALIAS).flattenToString())
        }
        if (prefs.getDpadDownApp() == null) {
            firstPackage("jp.kyocera.memo")?.let { prefs.setDpadDownApp(it) }
        }
        fun extra(keyCode: Int, vararg packages: String) {
            if (prefs.getExtraKeyApp(keyCode) == null) firstPackage(*packages)?.let { prefs.setExtraKeyApp(keyCode, it) }
        }
        extra(LauncherPrefs.KEYCODE_EXTRA_1, "com.flipweather.app") // SOS
        extra(LauncherPrefs.KEYCODE_EXTRA_4, "com.kyocera.flashlight") // PTT
        extra(LauncherPrefs.KEYCODE_EXTRA_2, "com.kyocera.timer") // outer END
        extra(LauncherPrefs.KEYCODE_EXTRA_3, "com.kyocera.alarm", "com.android.deskclock") // outer Speaker
        prefs.setDefaultKeysSeeded()
    }

    private fun mainKey(context: Context, packageName: String): String? = try {
        context.packageManager.getLaunchIntentForPackage(packageName)?.component?.flattenToString()
    } catch (e: Exception) {
        null
    }

    private fun categoryKey(context: Context, category: String): String? = try {
        context.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(category), 0)
            ?.activityInfo?.takeIf { it.packageName != CategoryApps.CHOOSER_PACKAGE }
            ?.let { mainKey(context, it.packageName) }
    } catch (e: Exception) {
        null
    }

    private fun installed(context: Context, packageName: String): Boolean = try {
        context.packageManager.getPackageInfo(packageName, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }
}
