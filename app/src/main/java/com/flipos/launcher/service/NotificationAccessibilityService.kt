package com.flipos.launcher.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.KeyguardManager
import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat
import com.flipos.launcher.R
import com.flipos.launcher.data.NotificationCategorizer
import com.flipos.launcher.data.NotificationKind
import com.flipos.launcher.data.NoticeItem
import com.flipos.launcher.data.NotificationStore
import com.flipos.launcher.data.NotificationText
import com.flipos.launcher.util.CategoryApps
import com.flipos.launcher.util.ReadAloud
import com.flipos.launcher.util.ReadAloudSpeaker
import com.flipos.launcher.util.SettingsRedirects
import com.flipos.launcher.util.SystemCallLog

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
 * ([NotificationStore.markSeen]) when it's posted while its app is on screen, or when its app
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

    /** The screen came back on or was unlocked with an app still in front: its entries are now seen. */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (NotificationCountService.instance != null) return
            val visible = isScreenVisible()
            Log.d(TAG, "${intent?.action}: visible=$visible foreground=$foregroundPackage")
            if (visible) foregroundPackage?.let { NotificationStore.markSeen(it) }
        }
    }
    private var screenReceiverRegistered = false

    /** Missed calls never arrive as an alerting notification here - see [MissedCallWatcher]. */
    private var missedCalls: MissedCallWatcher? = null

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
        if (missedCalls == null) {
            missedCalls = MissedCallWatcher(this) { caller -> postMissedCall(caller) }.also { it.start() }
        }
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
        missedCalls?.stop()
        missedCalls = null
        if (screenReceiverRegistered) {
            unregisterReceiver(screenReceiver)
            screenReceiverRegistered = false
        }
        super.onDestroy()
    }

    /** The package of the last window event, any window - for [handleSettingsRedirect]'s guard. */
    private var lastWindowPackage: String? = null

    /** When the last redirect opened, so a screen's repeated window events open ours only once. */
    private var lastRedirectAt = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // Runs whichever source feeds the banner, so it works on the E4610 too.
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) handleSettingsRedirect(event)
        // NotificationCountService already owns NotificationStore with richer,
        // accurately-active data whenever it's actually bound - don't fight over it.
        if (NotificationCountService.instance != null) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> handleForegroundChange(event)
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> handleNotificationPosted(event)
        }
    }

    /**
     * Opens the launcher's own screen over a phone Settings screen that has
     * one ([SettingsRedirects]) - unless the user just came from the launcher
     * (Back from ours, or a screen the launcher opened), so the phone's screen
     * stays reachable.
     */
    private fun handleSettingsRedirect(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return
        val className = event.className?.toString().orEmpty()
        val title = event.text.joinToString(" ")
        val previous = lastWindowPackage
        lastWindowPackage = packageName
        val target = SettingsRedirects.match(packageName, className, title) ?: return
        val now = SystemClock.elapsedRealtime()
        Log.d(TAG, "settings screen $className \"$title\" -> $target, previous=$previous")
        if (previous == this.packageName || now - lastRedirectAt < REDIRECT_DEBOUNCE_MS) return
        lastRedirectAt = now
        SettingsRedirects.open(this, target)
    }

    private fun handleForegroundChange(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return
        val className = event.className?.toString() ?: return
        val isApp = isActivity(packageName, className)
        Log.d(TAG, "window $packageName/$className \"${event.text.joinToString(" ")}\" app=$isApp foreground=$foregroundPackage")
        if (packageName == foregroundPackage || !isApp) return
        // The previous app was left - dismiss what the user saw of it.
        foregroundPackage?.let {
            val removed = NotificationStore.dismissSeen(it)
            Log.d(TAG, "left $it: dismissed $removed")
        }
        foregroundPackage = packageName
        if (isScreenVisible()) NotificationStore.markSeen(packageName)
    }

    /** Screen on and unlocked: what's in front is actually being looked at. */
    private fun isScreenVisible(): Boolean {
        val power = getSystemService(Context.POWER_SERVICE) as? PowerManager
        val keyguard = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        return power?.isInteractive != false && keyguard?.isKeyguardLocked != true
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
        val packageName = event.packageName?.toString() ?: return
        // This feed has no removals, so an ongoing notification (a ringing or
        // active call, music, a download) would linger here after it ends.
        if (notification.flags and Notification.FLAG_ONGOING_EVENT != 0) {
            val (ongoingTitle, ongoingText) = NotificationText.latest(notification)
            Log.d(TAG, "event $packageName ongoing category=${notification.category} title=\"$ongoingTitle\" text=\"$ongoingText\": skipped")
            // The ringing-call notification names the caller, in case the call goes unanswered.
            if (packageName in CategoryApps.phonePackages(this)) missedCalls?.ringingCaller = ongoingTitle
            return
        }
        // Group summaries count too: once a second message arrives, apps such
        // as TurboText alert with the summary and update the message itself
        // silently, so skipping summaries lost every message after the first.
        val (title, text) = NotificationText.latest(notification)
        if (title.isEmpty() && text.isEmpty()) return
        // The summary and the message itself can both arrive for one message.
        val now = System.currentTimeMillis()
        val duplicate = NotificationStore.items.any {
            it.packageName == packageName && it.text == text && now - it.postTime < DUPLICATE_WINDOW_MS
        }
        val kind = NotificationCategorizer.kindOf(
            notification.category, packageName, CategoryApps.calendarPackage(this), CategoryApps.phonePackages(this),
        )
        Log.d(TAG, "event $packageName category=${notification.category} kind=$kind summary=${notification.flags and Notification.FLAG_GROUP_SUMMARY != 0} duplicate=$duplicate")
        if (duplicate) return
        val newItem = NoticeItem(
            key = "$packageName:${event.eventTime}",
            packageName = packageName,
            title = title.ifEmpty { appLabel(packageName) },
            text = text,
            postTime = now,
            icon = null,
            kind = kind,
            contentIntent = notification.contentIntent,
        )
        post(newItem)
    }

    /**
     * Adds [newItem] to the store, replacing its app's own prior pending
     * entry but keeping every other app's - a notification from one app
     * shouldn't silently erase another's.
     */
    private fun post(newItem: NoticeItem, seenIfInFront: Boolean = true) {
        val packageName = newItem.packageName
        val merged = (NotificationStore.items.filterNot { it.packageName == packageName } + newItem)
            .sortedByDescending { it.postTime }
            .take(MAX_PENDING)
        NotificationStore.update(merged)
        // Posted while its app is on screen: the user is looking at it already.
        val seen = seenIfInFront && packageName == foregroundPackage && isScreenVisible()
        if (seen) NotificationStore.markSeenKey(newItem.key)
        Log.d(TAG, "posted $packageName seen=$seen foreground=$foregroundPackage")
    }

    /** A call rang out unanswered: a "Missed call" banner entry under the phone app, opening the call log. */
    private fun postMissedCall(caller: String?) {
        if (NotificationCountService.instance != null) return
        val dialer = CategoryApps.phonePackages(this).first()
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        val openCallLog = PendingIntent.getActivity(
            this,
            MISSED_CALL_REQUEST,
            SystemCallLog.intent(this).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            flags,
        )
        val now = System.currentTimeMillis()
        post(
            NoticeItem(
                key = "${MissedCallWatcher.KEY_PREFIX}$now",
                packageName = dialer,
                title = caller?.takeIf { it.isNotBlank() } ?: getString(R.string.missed_call_unknown_caller),
                text = getString(R.string.missed_call),
                postTime = now,
                icon = null,
                kind = NotificationKind.CALL,
                contentIntent = openCallLog,
            ),
            // The phone's own ringing screen may still count as "in front"
            // as the call ends; that isn't the user seeing the missed call.
            seenIfInFront = false,
        )
    }

    override fun onInterrupt() {}

    /**
     * Any button press anywhere (keypad, soft keys, side/outer buttons)
     * stops a readout in progress; the key still passes through to do its
     * normal job. (Bluetooth headset buttons reach the read-aloud
     * MediaSession instead.) Must stay cheap - it gates every
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

        /** A message's summary and its own notification arriving this close together are one message. */
        private const val DUPLICATE_WINDOW_MS = 5_000L

        /** PendingIntent request code for the missed-call entry's "open the call log". */
        private const val MISSED_CALL_REQUEST = 4810

        /** A screen's window events can repeat; one redirect per this long. */
        private const val REDIRECT_DEBOUNCE_MS = 1500L

        /** Logcat tag for the banner's seen/dismiss trail (filter `tag:FlipNotif`). */
        const val TAG = "FlipNotif"
    }
}
