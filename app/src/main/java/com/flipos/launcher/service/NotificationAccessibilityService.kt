package com.flipos.launcher.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Notification
import android.content.pm.PackageManager
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.flipos.launcher.data.NotificationCategorizer
import com.flipos.launcher.data.NoticeItem
import com.flipos.launcher.data.NotificationStore
import com.flipos.launcher.util.ReadAloud
import com.flipos.launcher.util.ReadAloudSpeaker

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
 * (see MAX_PENDING), rather than one global "currently active" set.
 *
 * An app's own entry also clears whenever the user opens it via the launcher
 * (see Context.launchAppByKey in util/Launch.kt) - but that only catches
 * launcher-mediated opens. This service additionally tracks the actual
 * foreground app itself via TYPE_WINDOW_STATE_CHANGED, so an app opened any
 * other way (a notification tap, task switcher, etc.) still gets its pending
 * entry cleared: never shown at all while the app is the one in front (a
 * banner for an app you're already looking at is just noise), and cleared on
 * exit as a safety net for anything the app posted about itself while still
 * in front (e.g. "download complete") that a background-only check would
 * otherwise leave stranded until the next unrelated notification event.
 */
class NotificationAccessibilityService : AccessibilityService() {

    private var foregroundPackage: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        // Mirror accessibility_service_config.xml in code too: some OEM builds
        // ignore (parts of) the XML, which silently leaves this service
        // receiving nothing.
        serviceInfo = (serviceInfo ?: AccessibilityServiceInfo()).apply {
            eventTypes = AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 100
            // Lets onKeyEvent see every button press, so one stops read-aloud.
            flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        }
        ReadAloud.attach(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // NotificationCountService already owns NotificationStore with richer,
        // accurately-active data whenever it's actually bound - don't fight over it.
        if (NotificationCountService.instance != null) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> handleForegroundChange(event)
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> handleNotificationPosted(event)
        }
    }

    private fun handleForegroundChange(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return
        if (packageName == foregroundPackage) return
        // Leaving the previous foreground app - drop anything it posted about
        // itself while it had the user's attention.
        foregroundPackage?.let { NotificationStore.removeItemsForPackage(it) }
        foregroundPackage = packageName
        // Entering this app - it shouldn't be showing its own stale banner.
        NotificationStore.removeItemsForPackage(packageName)
    }

    private fun handleNotificationPosted(event: AccessibilityEvent) {
        val notification = event.parcelableData as? Notification ?: return
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val packageName = event.packageName?.toString() ?: return
        // Already in front - no point banner-ing a notification for the app
        // the user is currently looking at.
        if (packageName == foregroundPackage) return
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

    /**
     * Any button press anywhere (keypad, soft keys, side/outer buttons,
     * Bluetooth headset keys) stops a readout in progress and is swallowed;
     * otherwise keys pass straight through. Must stay cheap - it gates every
     * key on the phone - which [ReadAloudSpeaker.interceptKey] is when idle.
     */
    override fun onKeyEvent(event: KeyEvent): Boolean = ReadAloudSpeaker.interceptKey(event)

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
