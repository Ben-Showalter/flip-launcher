package com.flipos.launcher.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.AlarmClock
import android.provider.Telephony
import android.telecom.TelecomManager

/**
 * Best-effort "app that handles X on this device" resolvers, used both to
 * seed the app grid's one-time default order and as fallbacks for Home's
 * physical-key defaults. Every function returns a component key (see
 * [com.flipos.launcher.data.AppInfo.key]) or null if nothing resolves on
 * this device - never throws.
 */
@Suppress("DEPRECATION") // int-flags resolveActivity() overload kept for minSdk 21 compatibility
object CategoryApps {

    /** The default phone/dialer app's main launch component, or null. */
    fun dialerKey(context: Context): String? {
        val packageName = if (Build.VERSION.SDK_INT >= 23) {
            (context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager)?.defaultDialerPackage
        } else {
            null
        } ?: resolvePackage(context, Intent(Intent.ACTION_DIAL))
        return packageName?.let { launchKeyFor(context, it) }
    }

    /** The default SMS app's main launch component, or null. */
    fun smsKey(context: Context): String? =
        Telephony.Sms.getDefaultSmsPackage(context)?.let { launchKeyFor(context, it) }

    fun contactsKey(context: Context): String? = categoryKey(context, Intent.CATEGORY_APP_CONTACTS)
    fun galleryKey(context: Context): String? = categoryKey(context, Intent.CATEGORY_APP_GALLERY)
    fun calendarKey(context: Context): String? = categoryKey(context, Intent.CATEGORY_APP_CALENDAR)
    fun calculatorKey(context: Context): String? = categoryKey(context, Intent.CATEGORY_APP_CALCULATOR)

    /** File manager, only resolvable from API 29 (when CATEGORY_APP_FILES was added). */
    fun filesKey(context: Context): String? {
        if (Build.VERSION.SDK_INT < 29) return null
        return categoryKey(context, Intent.CATEGORY_APP_FILES)
    }

    /**
     * The system's Timer target. Unlike the other resolvers this can be a
     * deep, non-launcher-main activity - fine since it's only ever used as a
     * key default, never as an app-drawer/seeding entry.
     */
    fun timerKey(context: Context): String? {
        if (Build.VERSION.SDK_INT < 23) return null
        val ai = context.packageManager.resolveActivity(Intent(AlarmClock.ACTION_SHOW_TIMERS), 0)?.activityInfo
            ?: return null
        return ComponentName(ai.packageName, ai.name).flattenToString()
    }

    private fun categoryKey(context: Context, category: String): String? {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(category)
        val ai = context.packageManager.resolveActivity(intent, 0)?.activityInfo ?: return null
        return ComponentName(ai.packageName, ai.name).flattenToString()
    }

    private fun resolvePackage(context: Context, intent: Intent): String? =
        context.packageManager.resolveActivity(intent, 0)?.activityInfo?.packageName

    private fun launchKeyFor(context: Context, packageName: String): String? =
        context.packageManager.getLaunchIntentForPackage(packageName)?.component?.flattenToString()
}
