package com.flipos.launcher.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.PowerManager
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
 * Every notification is posted, whatever app is in front (which ones the
 * Home banner and read-aloud then use is LauncherPrefs.isShownOnHome's
 * call). An app's entries are dismissed when the user leaves that app: this
 * tracks the foreground app via TYPE_WINDOW_STATE_CHANGED, counting only
 * windows that are one of the app's own Activities - other windows (dialogs,
 * toasts, the keyboard, SystemUI, and outer-screen popups like TurboText's,
 * which fire just before the app's notification) would otherwise look like
 * the app opening and closing and wipe the message it just posted. With the
 * screen off (flip closed) nothing counts as in front: the app left open
 * when the flip closed is treated as closed then.
 */
class NotificationAccessibilityService : AccessibilityService() {

    private var foregroundPackage: String? = null

    /** "pkg/class" -> whether it's one of that package's Activities; window events repeat, so cache. */
    private val isActivityCache = HashMap<String, Boolean>()

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
        val className = event.className?.toString() ?: return
        if (packageName == foregroundPackage || !isActivity(packageName, className)) return
        // The previous app was closed (left) - dismiss what it posted.
        foregroundPackage?.let { NotificationStore.removeItemsForPackage(it) }
        foregroundPackage = packageName
    }

    private fun isActivity(packageName: String, className: String): Boolean =
        isActivityCache.getOrPut("$packageName/$className") {
            try {
                packageManager.getActivityInfo(ComponentName(packageName, className), 0)
                true
            } catch (e: PackageManager.NameNotFoundException) {
                false
            }
        }

    private fun handleNotificationPosted(event: AccessibilityEvent) {
        val notification = event.parcelableData as? Notification ?: return
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val packageName = event.packageName?.toString() ?: return
        // Screen off (flip closed): whatever app was left in front counts as
        // closed, so its old entries go now - not when Home appears on the
        // next flip open, which would also take this new one with them.
        val power = getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (power?.isInteractive == false) {
            foregroundPackage?.let { NotificationStore.removeItemsForPackage(it) }
            foregroundPackage = null
        }
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
