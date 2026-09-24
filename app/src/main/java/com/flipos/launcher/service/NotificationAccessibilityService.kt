package com.flipos.launcher.service

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.content.pm.PackageManager
import android.view.accessibility.AccessibilityEvent
import com.flipos.launcher.data.NotificationCategorizer
import com.flipos.launcher.data.NoticeItem
import com.flipos.launcher.data.NotificationStore

/**
 * Fallback notification source for devices where NotificationListenerService
 * access is granted per Settings.Secure but never actually binds (confirmed
 * on Kyocera E4810/E4811, Android 9/10 - see AGENTS.md). Accessibility events
 * are a separate OS subsystem from the notification-listener allowlist that
 * blocks those devices, so this reaches notifications the normal path can't.
 *
 * Deliberately limited to the Home banner, not the full Notices list: unlike
 * NotificationListenerService.getActiveNotifications(), there's no
 * accessibility equivalent of "list everything currently active," and no
 * removal event either - just a stream of "this was just posted" events. So
 * this keeps a small stack of the most recent pending notification per app
 * (see MAX_PENDING), rather than one global "currently active" set; an app's
 * own entry clears when the user opens that app (see
 * Context.launchAppByKey in util/Launch.kt), not when the underlying
 * notification is actually dismissed/read elsewhere.
 */
class NotificationAccessibilityService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) return
        // NotificationCountService already owns NotificationStore with richer,
        // accurately-active data whenever it's actually bound - don't fight over it.
        if (NotificationCountService.instance != null) return
        val notification = event.parcelableData as? Notification ?: return
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val packageName = event.packageName?.toString() ?: return
        val extras = notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        if (title.isEmpty() && text.isEmpty()) return
        val newItem = NoticeItem(
            key = "$packageName:${event.eventTime}",
            packageName = packageName,
            title = title.ifEmpty { appLabel(packageName) },
            text = text,
            postTime = System.currentTimeMillis(),
            icon = null,
            kind = NotificationCategorizer.kindOf(notification.category),
        )
        // Replace this app's own prior pending entry, but keep every other app's -
        // a notification from one app shouldn't silently erase another's.
        val merged = (NotificationStore.items.filterNot { it.packageName == packageName } + newItem)
            .sortedByDescending { it.postTime }
            .take(MAX_PENDING)
        NotificationStore.update(merged)
    }

    override fun onInterrupt() {}

    private fun appLabel(packageName: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        packageName
    }

    companion object {
        /** Defensive cap on how many distinct apps' pending notifications this stacks. */
        private const val MAX_PENDING = 10
    }
}
