package com.flipos.launcher.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.service.notification.NotificationListenerService
import com.flipos.launcher.service.NotificationAccessibilityService
import com.flipos.launcher.service.NotificationCountService

/** Whether this launcher holds Notification Access (per Settings.Secure - see AGENTS.md on Kyocera). */
fun Context.isNotificationAccessGranted(): Boolean {
    val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
    return flat.split(':').any { ComponentName.unflattenFromString(it)?.packageName == packageName }
}

/** Whether [NotificationAccessibilityService] is turned on in the phone's Accessibility settings. */
fun Context.isAccessibilityServiceEnabled(): Boolean {
    val target = ComponentName(this, NotificationAccessibilityService::class.java)
    val flat = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
    return flat.split(':').any { ComponentName.unflattenFromString(it) == target }
}

/** Whether this launcher is the phone's default Home app. */
fun Context.isDefaultLauncher(): Boolean {
    val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
    @Suppress("DEPRECATION")
    val resolved = packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
    return resolved == packageName
}

/**
 * The OS doesn't always redeliver onListenerConnected() after an app
 * update/reinstall, even though access was already granted - Settings.Secure
 * still lists us but NotificationCountService.instance stays null forever,
 * silently keeping Notices and the Home badges empty. Detect that state and
 * proactively ask the framework to rebind, like
 * NotificationCountService.onListenerDisconnected() does for a mid-session drop.
 */
fun Context.requestListenerRebindIfStale() {
    if (!isNotificationAccessGranted() || NotificationCountService.instance != null) return
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        try {
            NotificationListenerService.requestRebind(ComponentName(this, NotificationCountService::class.java))
        } catch (e: Exception) {
            // Best-effort; the framework rebinds on its own schedule anyway.
        }
    }
}
