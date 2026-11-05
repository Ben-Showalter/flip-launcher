package com.flipos.launcher.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.KeyguardManager
import android.app.Notification
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.PowerManager
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat
import com.flipos.launcher.data.NotificationCategorizer
import com.flipos.launcher.data.NoticeItem
import com.flipos.launcher.data.NotificationStore
import com.flipos.launcher.util.CategoryApps
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
 * call). An entry is dismissed once the user has seen its app and then left
 * it. This tracks the foreground app via TYPE_WINDOW_STATE_CHANGED, counting
 * only windows that are one of the app's own Activities - other windows
 * (dialogs, toasts, the keyboard, SystemUI, and outer-screen popups like
 * TurboText's, which fire just before the app's notification) would
 * otherwise look like the app opening and closing. An entry counts as seen
 * ([seenKeys]) when it's posted while its app is on screen, or when its app
 * comes on screen: a window change, or the screen coming back on / unlocking
 * with the app still in front (opening the flip onto an app fires no window
 * event, which used to leave its entry stuck). A message that arrives with
 * the flip closed isn't seen yet, so it survives Home appearing on the next
 * open, and goes once the user has opened its app and left it.
 */
class NotificationAccessibilityService : AccessibilityService() {

    private var foregroundPackage: String? = null

    /** "pkg/class" -> whether it's one of that package's Activities; window events repeat, so cache. */
    private val isActivityCache = HashMap<String, Boolean>()

    /** Keys of [NotificationStore] entries the user has seen their app with; dismissed on leaving it. */
    private val seenKeys = HashSet<String>()

    /** The screen came back on or was unlocked with an app still in front: its entries are now seen. */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (NotificationCountService.instance != null) return
            if (isScreenVisible()) foregroundPackage?.let { markSeen(it) }
        }
    }
    private var screenReceiverRegistered = false

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
        if (!screenReceiverRegistered) {
            // Exported on purpose: SCREEN_ON/USER_PRESENT are platform broadcasts,
            // and below Android 13 RECEIVER_NOT_EXPORTED guards the receiver with
            // an app-private permission that blocks USER_PRESENT (sent by
            // SystemUI, an app uid) - the unlock then never arrives.
            ContextCompat.registerReceiver(
                this,
                screenReceiver,
                IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_ON)
                    addAction(Intent.ACTION_USER_PRESENT)
                },
                ContextCompat.RECEIVER_EXPORTED,
            )
            screenReceiverRegistered = true
        }
    }

    override fun onDestroy() {
        if (screenReceiverRegistered) {
            unregisterReceiver(screenReceiver)
            screenReceiverRegistered = false
        }
        super.onDestroy()
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
        // The previous app was left - dismiss what the user saw of it.
        foregroundPackage?.let { dismissSeen(it) }
        foregroundPackage = packageName
        if (isScreenVisible()) markSeen(packageName)
    }

    /** Screen on and unlocked: what's in front is actually being looked at. */
    private fun isScreenVisible(): Boolean {
        val power = getSystemService(Context.POWER_SERVICE) as? PowerManager
        val keyguard = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        return power?.isInteractive != false && keyguard?.isKeyguardLocked != true
    }

    private fun markSeen(packageName: String) {
        NotificationStore.items.filter { it.packageName == packageName }.forEach { seenKeys.add(it.key) }
    }

    private fun dismissSeen(packageName: String) {
        val kept = NotificationStore.items.filterNot { it.packageName == packageName && it.key in seenKeys }
        if (kept.size != NotificationStore.items.size) NotificationStore.update(kept)
        seenKeys.retainAll(kept.map { it.key }.toSet())
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
            kind = NotificationCategorizer.kindOf(notification.category, packageName, CategoryApps.calendarPackage(this)),
            contentIntent = notification.contentIntent,
        )
        // Replace this app's own prior pending entry, but keep every other app's -
        // a notification from one app shouldn't silently erase another's.
        val merged = (NotificationStore.items.filterNot { it.packageName == packageName } + newItem)
            .sortedByDescending { it.postTime }
            .take(MAX_PENDING)
        // Posted while its app is on screen: the user is looking at it already.
        if (packageName == foregroundPackage && isScreenVisible()) seenKeys.add(newItem.key)
        seenKeys.retainAll(merged.map { it.key }.toSet())
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
