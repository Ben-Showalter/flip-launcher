package com.flipos.launcher.activities

import com.flipos.launcher.R

import android.Manifest
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.MediaStore
import android.provider.Settings
import android.speech.RecognizerIntent
import android.view.KeyEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import com.flipos.launcher.data.AppRepository
import com.flipos.launcher.data.IconShapeRenderer
import com.flipos.launcher.data.LauncherPrefs
import com.flipos.launcher.data.NotificationKind
import com.flipos.launcher.data.NotificationStore
import com.flipos.launcher.data.UpdateChecker
import com.flipos.launcher.util.BackgroundLoader
import com.flipos.launcher.util.CategoryApps
import com.flipos.launcher.util.KEYCODE_ASSISTANT_RAW
import com.flipos.launcher.util.KyoceraShortcuts
import com.flipos.launcher.util.PermissionGate
import com.flipos.launcher.util.ReadAloud
import com.flipos.launcher.util.ReadAloudSpeaker
import com.flipos.launcher.util.WallpaperContrast
import com.flipos.launcher.util.accentColorAlpha
import com.flipos.launcher.util.applyFakeBold
import com.flipos.launcher.util.hideNavigationBar
import com.flipos.launcher.util.isAccessibilityServiceEnabled
import com.flipos.launcher.util.isDefaultLauncher
import com.flipos.launcher.util.isStarKey
import com.flipos.launcher.util.outerKeyFor
import com.flipos.launcher.util.launchAppByKey
import com.flipos.launcher.util.placeCall
import com.flipos.launcher.util.SystemSpeedDialResult
import com.flipos.launcher.util.openSystemSpeedDial
import com.flipos.launcher.util.openSettingsWithPath
import com.flipos.launcher.util.showUpdatePrompt
import com.flipos.launcher.util.systemSpeedDial
import com.flipos.launcher.util.toastIfUnknownKey
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The KaiOS-style home screen:
 *  - a large clock + date, and
 *  - soft keys along the bottom (Contacts on the left, the default messaging
 *    app on the right, unless overridden in Home Shortcuts settings), with a
 *    D-pad/Camera shortcut icon pod nested between the two labels and the
 *    App-Drawer/OK button sitting in its center.
 *
 * Typing a digit - or `*` / `#` - anywhere on Home opens the phone dialer
 * prefilled with it. The center button opens All Apps; long-pressing it (via
 * touch) opens Settings.
 *
 * Every physical key (digits 0/2-9, MENU, BACK, the soft keys, D-pad
 * Up/Down/Left/Right, Camera) also carries a long-press action and a
 * 5-second-hold "assign" menu - see the Key handling section below. Digits
 * are the exception: their long-press (speed dial / voicemail) fires the
 * instant it's detected, and assignment for them is Settings-only. The
 * phone's outer buttons (SOS, outer END/Speaker, PTT) open their assigned
 * app on a single press, only while Home is showing with the screen on and
 * the flip open; unassigned, they're left to the system's own key setting.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var prefs: LauncherPrefs
    private lateinit var appMenuButton: ImageView
    private lateinit var clock: TextView
    private lateinit var ampm: TextView
    private lateinit var weekday: TextView
    private lateinit var dateLine: TextView
    private lateinit var notifBanner: View
    private lateinit var notifBannerIcon: ImageView
    private lateinit var notifBannerApp: TextView
    private lateinit var notifBannerText: TextView
    private lateinit var dpadIconUp: ImageView
    private lateinit var dpadIconDown: ImageView
    private lateinit var dpadIconLeft: ImageView
    private lateinit var dpadIconRight: ImageView

    private val ampmFmt = SimpleDateFormat("a", Locale.getDefault())
    private val time12 = SimpleDateFormat("h:mm", Locale.getDefault())
    private val time24 = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val weekdayFmt = SimpleDateFormat("EEEE", Locale.getDefault())
    private val dateFmt = SimpleDateFormat("MMM d", Locale.getDefault())

    /** Physical key awaiting an app from [assignAppLauncher] (0 = none). */
    private var pendingAssignAppKey = 0

    /** The accent color applied this onCreate, so [onResume] can detect a change and [recreate]. */
    private var appliedAccentColor: LauncherPrefs.AccentColor? = null

    // ---------------------------------------------------- Key hold tracking
    //
    // Every assignable physical key (digits 0/2-9, MENU, BACK, the soft keys)
    // shares one small state machine: a short tap performs the key's default
    // action, holding past the framework's long-press threshold (~500ms)
    // performs its bound action instead (dial a speed-dial number / launch a
    // bound app), and holding for a full 5 seconds opens that key's assign
    // menu. See onKeyDown/onKeyUp below.

    private val assignHandler = Handler(Looper.getMainLooper())

    /** Auto-hides a low-priority ("Other") banner item after a short delay - see [updateNotifBanner]. */
    private val notifBannerHandler = Handler(Looper.getMainLooper())
    private val hideNotifBannerRunnable = Runnable { notifBanner.visibility = View.GONE }

    /** Scheduled 5-second assign runnables, keyed by keyCode, so a release can cancel them. */
    private val assignRunnables = HashMap<Int, Runnable>()

    /** Scheduled digit-long-press (speed dial / voicemail) runnables, keyed by keyCode. */
    private val digitHoldRunnables = HashMap<Int, Runnable>()

    /** [KeyEvent.getDownTime] of the outer-button press that last launched, keyed by synthetic keycode. */
    private val extraKeyFiredDownTime = HashMap<Int, Long>()

    /** Scheduled digit short-tap dial runnables (debounced against a same-key re-press), keyed by keyCode. */
    private val digitTapRunnables = HashMap<Int, Runnable>()

    /**
     * [KeyEvent.getDownTime] of the digit press session already resolved
     * (hold fired, or a tap already scheduled), keyed by keyCode. Some
     * hardware delivers a spurious extra ACTION_UP mid-hold (with a repeat
     * ACTION_DOWN in between, same downTime) for what is really one
     * continuous press - this lets a second UP for a downTime we've already
     * handled be ignored instead of re-triggering the tap action.
     */
    private val digitHandledDownTime = HashMap<Int, Long>()

    /** Keycodes whose 5-second assign menu already fired for the current press. */
    private val assignFired = HashSet<Int>()

    /** Keycodes that have crossed the framework long-press threshold for the current press. */
    private val longPressFired = HashSet<Int>()

    /**
     * CALL / `*` / `#` presses whose DOWN landed on Home, so their action runs
     * on UP - and only for a real press, never a stray UP left over from
     * another window.
     */
    private val pressedTapKeys = HashSet<Int>()

    private val callPermission = PermissionGate(this, Manifest.permission.CALL_PHONE)
    private val contactsPermission = PermissionGate(this, Manifest.permission.READ_CONTACTS)

    /** App picker for MENU/BACK/soft-key/D-pad/Camera assignment, writing back based on [pendingAssignAppKey]. */
    private val assignAppLauncher = registerForActivityResult(StartActivityForResult()) { result ->
        val key = result.data?.getStringExtra(AppPickerActivity.EXTRA_APP_KEY)
        val target = pendingAssignAppKey
        pendingAssignAppKey = 0
        if (result.resultCode == RESULT_OK && key != null) {
            when (target) {
                KeyEvent.KEYCODE_MENU -> prefs.setMenuKeyApp(key)
                KeyEvent.KEYCODE_BACK -> prefs.setBackLongPressApp(key)
                KeyEvent.KEYCODE_SOFT_LEFT -> prefs.setLeftKeyApp(key)
                KeyEvent.KEYCODE_SOFT_RIGHT -> prefs.setRightKeyApp(key)
                KeyEvent.KEYCODE_DPAD_UP -> prefs.setDpadUpApp(key)
                KeyEvent.KEYCODE_DPAD_DOWN -> prefs.setDpadDownApp(key)
                KeyEvent.KEYCODE_DPAD_LEFT -> prefs.setDpadLeftApp(key)
                KeyEvent.KEYCODE_DPAD_RIGHT -> prefs.setDpadRightApp(key)
                KeyEvent.KEYCODE_CAMERA, LauncherPrefs.KEYCODE_CAMERA_ALT, LauncherPrefs.KEYCODE_CAMERA_ALT2 -> prefs.setCameraKeyApp(key)
                KeyEvent.KEYCODE_F4, KEYCODE_ASSISTANT_RAW -> prefs.setAssistantKeyApp(key)
                in LauncherPrefs.EXTRA_KEYCODES -> prefs.setExtraKeyApp(target, key)
            }
            AppRepository.resolveComponent(this, key)?.label?.let {
                Toast.makeText(this, getString(R.string.key_assigned_toast, it), Toast.LENGTH_SHORT).show()
            }
            refreshLeftKeyLabel()
            refreshRightKeyLabel()
            refreshDirectionalPod()
        }
    }

    private val loader = BackgroundLoader()

    /** Measures the wallpaper for [applyWallpaperScrim]; separate from [loader] so neither cancels the other. */
    private val scrimLoader = BackgroundLoader()

    /** Separate from [loader] so its other loads can't supersede an update check/download. */
    private val updateLoader = BackgroundLoader()

    private val timeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = updateClock()
    }

    /** Keeps cached icons and the D-pad pod fresh when apps are installed/removed/updated. */
    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // On a genuine uninstall (not an update's remove-then-add), drop any
            // per-app icon overrides for the departed package so they don't leak.
            if (intent?.action == Intent.ACTION_PACKAGE_REMOVED &&
                intent.getBooleanExtra(Intent.EXTRA_REPLACING, false).not()
            ) {
                intent.data?.schemeSpecificPart?.let { prefs.pruneIconOverridesForPackage(it) }
            }
            AppRepository.invalidateIconCaches()
            refreshDirectionalPod()
        }
    }

    private val notifListener: () -> Unit = {
        runOnUiThread { updateNotifBanner() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = LauncherPrefs(this)
        ReadAloud.attach(this)
        KyoceraShortcuts.syncEnabled(this)
        val accent = prefs.getAccentColor()
        appliedAccentColor = accent
        if (accent.themeOverlayRes != 0) theme.applyStyle(accent.themeOverlayRes, true)
        // No motion anywhere: instant navigation is snappier on these phones.
        theme.applyStyle(R.style.ThemeOverlay_FlipLauncher_NoAnimations, true)
        setContentView(R.layout.activity_main)
        findViewById<View>(android.R.id.content).applyFakeBold()

        clock = findViewById(R.id.clock)
        ampm = findViewById(R.id.ampm)
        weekday = findViewById(R.id.weekday)
        dateLine = findViewById(R.id.date_line)
        notifBanner = findViewById<View>(R.id.notif_banner).apply {
            backgroundTintList = ColorStateList.valueOf(accentColorAlpha(0xE6))
        }
        notifBannerIcon = findViewById(R.id.notif_banner_icon)
        notifBannerApp = findViewById(R.id.notif_banner_app)
        notifBannerText = findViewById(R.id.notif_banner_text)
        dpadIconUp = findViewById(R.id.dpad_icon_up)
        dpadIconDown = findViewById(R.id.dpad_icon_down)
        dpadIconLeft = findViewById(R.id.dpad_icon_left)
        dpadIconRight = findViewById(R.id.dpad_icon_right)

        findViewById<TextView>(R.id.softkey_left).setOnClickListener { openLeftKeyApp() }
        findViewById<TextView>(R.id.softkey_right).setOnClickListener { openRightKeyApp() }
        appMenuButton = findViewById<ImageView>(R.id.softkey_center).apply {
            // bg_rail_focus is white so it can be tinted to the user's accent.
            backgroundTintList = ColorStateList.valueOf(accentColorAlpha(0x4D))
            setOnClickListener { openAppDrawer() }
            setOnLongClickListener { openOptions(); true }
        }

        // Back opens the app drawer; long-pressing it launches the configured app instead
        // (see onKeyDown/onKeyUp, which suppress this callback when a long-press fires).
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = openAppDrawer()
        })

        updateClock()
    }

    override fun onResume() {
        super.onResume()
        window.hideNavigationBar()
        applyWallpaperScrim()
        ContextCompat.registerReceiver(
            this,
            timeReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_TIME_TICK)
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        ContextCompat.registerReceiver(
            this,
            packageReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_CHANGED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addDataScheme("package")
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        updateClock()
        refreshLeftKeyLabel()
        refreshRightKeyLabel()
        refreshDirectionalPod()
        focusAppMenu()
        NotificationStore.addListener(notifListener)
        updateNotifBanner()
        maybeShowStartupPrompts()
        // The accent color may have changed in Settings while Home was backgrounded;
        // theme overlays only apply at onCreate, so recreate to pick it up. Done last
        // (after registering the receiver/listener above) so onPause's matching
        // unregister calls below still have something to unregister.
        if (prefs.getAccentColor() != appliedAccentColor) recreate() else maybeCheckForUpdate()
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(timeReceiver)
        unregisterReceiver(packageReceiver)
        NotificationStore.removeListener(notifListener)
        notifBannerHandler.removeCallbacksAndMessages(null)
        // A key hold that's interrupted mid-press (screen off, app switch) may
        // never deliver a matching key-up; drop any scheduled assign timers so
        // they don't fire into the background.
        assignHandler.removeCallbacksAndMessages(null)
        assignRunnables.clear()
        digitHoldRunnables.clear()
        digitTapRunnables.clear()
        pressedTapKeys.clear()
        // digitHandledDownTime deliberately survives a pause: placing a
        // speed-dial call (from a hold firing) backgrounds this Activity via
        // the system InCallActivity, triggering this very onPause() while
        // more events for the same physical press are still arriving -
        // clearing it here reopens the double-dial window it exists to
        // close. Each new press already clears its own entry in onKeyDown.
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            window.hideNavigationBar()
            return
        }
        // A dialog (or closing the flip) can swallow a held key's UP, so stop
        // any hold timers now rather than let them fire behind the new window.
        // Pending digit *tap* dials (already released) and longPressFired /
        // digitHandledDownTime are left alone - see onPause for why the
        // latter must survive a speed-dial call taking focus.
        assignRunnables.keys.toList().forEach { cancelAssign(it) }
        digitHoldRunnables.keys.toList().forEach { cancelDigitHold(it) }
        pressedTapKeys.clear()
    }

    /**
     * Darkens the window behind this screen more over a bright wallpaper
     * and less over a dark one, so text and icons keep their contrast -
     * see [WallpaperContrast]. The theme's fixed scrim shows until the
     * (cached-per-wallpaper) measurement arrives.
     */
    private fun applyWallpaperScrim() {
        val appContext = applicationContext
        scrimLoader.load(
            produce = { WallpaperContrast.brightness(appContext) },
            consume = { brightness ->
                if (!isDestroyed) window.setBackgroundDrawable(WallpaperContrast.homeScrim(this, brightness))
            },
        )
    }

    override fun onDestroy() {
        loader.cancel()
        scrimLoader.cancel()
        updateLoader.cancel()
        super.onDestroy()
    }

    // ----------------------------------------------------------- Data / clock

    private fun updateClock() {
        val now = Date()
        if (android.text.format.DateFormat.is24HourFormat(this)) {
            ampm.visibility = TextView.GONE
            clock.text = time24.format(now)
        } else {
            ampm.visibility = TextView.VISIBLE
            ampm.text = ampmFmt.format(now)
            clock.text = time12.format(now)
        }
        weekday.text = weekdayFmt.format(now)
        dateLine.text = dateFmt.format(now)
    }

    // --------------------------------------------------------------- Actions

    /** Open the phone dialer, prefilled with the pressed digit (or * / #). */
    private fun startDial(digit: String) {
        try {
            startActivity(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", digit, null)))
        } catch (e: Exception) {
            Toast.makeText(this, R.string.toast_no_dialer, Toast.LENGTH_SHORT).show()
        }
    }

    private fun openAppDrawer() = startActivity(Intent(this, AppDrawerActivity::class.java))

    /** Highlight the "open app menu" button by default whenever Home is shown. */
    private fun focusAppMenu() = appMenuButton.post { appMenuButton.requestFocus() }

    private fun openLeftKeyApp() {
        val key = prefs.getLeftKeyApp()
        if (key != null) {
            launchAppByKey(key)
            return
        }
        val fallback = CategoryApps.contactsKey(this)
        if (fallback != null) {
            launchAppByKey(fallback)
        } else {
            Toast.makeText(this, R.string.directional_key_unset_toast, Toast.LENGTH_SHORT).show()
            openKeySettings(HomeKeysSettingsActivity.GROUP_NAVIGATION)
        }
    }

    private fun openOptions() = startActivity(Intent(this, SettingsActivity::class.java))

    private fun openRightKeyApp() {
        val key = prefs.getRightKeyApp()
        if (key != null) {
            launchAppByKey(key)
            return
        }
        val fallback = CategoryApps.smsKey(this)
        if (fallback != null) {
            launchAppByKey(fallback)
        } else {
            Toast.makeText(this, R.string.directional_key_unset_toast, Toast.LENGTH_SHORT).show()
            openKeySettings(HomeKeysSettingsActivity.GROUP_NAVIGATION)
        }
    }

    /**
     * Shows the single most recent active notification (across the enabled
     * categories) as a big, hard-to-miss bar. A low-priority ("Other")
     * notification auto-hides itself after [OTHER_NOTIF_AUTO_HIDE_MS] - calls
     * and messages persist until something else replaces or clears them.
     */
    private fun updateNotifBanner() {
        notifBannerHandler.removeCallbacks(hideNotifBannerRunnable)
        // The newest shown notification, even if older ones are still pending.
        val item = NotificationStore.newest { prefs.isShownOnHome(it.kind) }
        if (item == null) {
            notifBanner.visibility = View.GONE
            return
        }
        val (iconRes, cdRes) = when (item.kind) {
            NotificationKind.CALL -> R.drawable.ic_call to R.string.cd_notif_calls
            NotificationKind.MESSAGE -> R.drawable.ic_message to R.string.cd_notif_messages
            NotificationKind.OTHER -> R.drawable.ic_notification to R.string.cd_notif_other
        }
        val hideText = prefs.isNotificationTextHidden()
        val appName = if (hideText) appLabel(item.packageName) else item.title
        notifBanner.visibility = View.VISIBLE
        notifBannerIcon.setImageResource(iconRes)
        notifBannerApp.text = appName
        if (!hideText && item.text.isNotEmpty()) {
            notifBannerText.text = item.text
            notifBannerText.visibility = View.VISIBLE
        } else {
            notifBannerText.visibility = View.GONE
        }
        notifBanner.contentDescription = if (notifBannerText.visibility == View.VISIBLE) {
            "$appName: ${item.text}"
        } else {
            "$appName, ${getString(cdRes)}"
        }
        if (item.kind == NotificationKind.OTHER) {
            notifBannerHandler.postDelayed(hideNotifBannerRunnable, OTHER_NOTIF_AUTO_HIDE_MS)
        }
    }

    /**
     * Resolves [packageName]'s own installed label - deliberately not
     * NoticeItem.title, which is often a sender's name rather than the
     * app's, so the "hide message text" privacy toggle's promise to keep
     * only the app name actually holds.
     */
    private fun appLabel(packageName: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        packageName
    }

    private fun refreshRightKeyLabel() {
        val key = prefs.getRightKeyApp()
        val label = key?.let { AppRepository.resolveComponent(this, it)?.label }
            ?: getString(R.string.softkey_messages)
        findViewById<TextView>(R.id.softkey_right).text = label
        bindKeyIcon(findViewById(R.id.softkey_right_icon), key)
    }

    private fun refreshLeftKeyLabel() {
        val key = prefs.getLeftKeyApp()
        val label = key?.let { AppRepository.resolveComponent(this, it)?.label }
            ?: getString(R.string.softkey_contacts)
        findViewById<TextView>(R.id.softkey_left).text = label
        bindKeyIcon(findViewById(R.id.softkey_left_icon), key)
    }

    /**
     * The KaiOS "Recent Calls" action for the Send/Call key. Tries this
     * hardware's own call log screen first - the Kyocera "Kc" dialer
     * activity, whose package path differs by model (see
     * [SYSTEM_CALL_LOG_COMPONENTS]) - falling back to our own
     * [CallLogActivity] if none is present (any other device).
     */
    private fun openCallLog() {
        for (component in SYSTEM_CALL_LOG_COMPONENTS) {
            try {
                startActivity(
                    Intent(Intent.ACTION_MAIN)
                        .setComponent(component)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                return
            } catch (e: Exception) {
                // Not on this model (or not exported); try the next one.
            }
        }
        startActivity(Intent(this, CallLogActivity::class.java))
    }

    // ----------------------------------------------------------- Key handling
    //
    // Digits 1-9/0 fire their long-press action (dial a speed-dial number,
    // dial voicemail) the instant the framework's long-press threshold
    // crosses, like a real feature phone - see the digit branches below.
    // Digit assignment is handled entirely by the phone's own Speed Dial
    // settings (see dialSpeedDial), not this app; there's no in-place hold
    // for them.
    //
    // Every other assignable key (MENU, BACK, the soft keys, D-pad, Camera)
    // keeps the original model: swallowed on key-down, resolved on key-up -
    // launching anything on key-down leaves the matching key-up to be
    // delivered to whatever gets focused as a result, which on some devices
    // re-enters the same input. Key-down starts long-press tracking and the
    // 5-second assign timer; key-up decides between a tap, a long-press
    // action, or (if the timer already fired) nothing further.
    //
    // KEYCODE_DPAD_UP/DOWN/LEFT/RIGHT are exactly the keys Android's default
    // focus-search machinery intercepts at the currently-focused View, before
    // an unconsumed event would ever reach onKeyDown/onKeyUp below - so they
    // (and Camera, for uniformity) are captured a level higher, in
    // dispatchKeyEvent, before the view hierarchy gets a look at them.
    //
    // The phone's outer buttons have no reliable KeyEvent.KEYCODE_* of their
    // own, so they're identified by raw scan code (see util/Keys.kt
    // outerKeyFor) and handled in [onOuterKeyEvent]. These phones report
    // only the press - never how long it's held - so a long press can't be
    // detected; instead an assigned button opens its app on a single press,
    // but only when it's safe to (see [outerKeyBlockReason]), so a press in a
    // pocket or with the flip closed does nothing. There's no hold-to-assign
    // (Settings only). One with no app assigned is reported unhandled, so
    // the system's own binding (or default) applies - on the E4610 that
    // binding is a Kyocera Home feature that never fires under another
    // launcher, hence assigning them here.

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // A press while a message is being read only stops the readout.
        if (ReadAloudSpeaker.interceptKey(event)) return true
        val outerKey = outerKeyFor(event.scanCode)
        if (outerKey != null) {
            if (prefs.getExtraKeyApp(outerKey) == null) return false
            onOuterKeyEvent(outerKey, event)
            return true
        }
        if (event.keyCode in DIRECTIONAL_KEYS) {
            return when (event.action) {
                KeyEvent.ACTION_DOWN -> onKeyDown(event.keyCode, event)
                KeyEvent.ACTION_UP -> onKeyUp(event.keyCode, event)
                else -> super.dispatchKeyEvent(event)
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> {
                if (event.repeatCount == 0) {
                    cancelDigitTap(keyCode)
                    longPressFired.remove(keyCode)
                    digitHandledDownTime.remove(keyCode)
                    scheduleDigitHold(keyCode)
                }
                return true
            }
            // Resolved on UP: launching the dialer / Recent Calls on DOWN would
            // hand it our UP (a second "*", or a CALL that dials).
            KeyEvent.KEYCODE_STAR, KeyEvent.KEYCODE_NUMPAD_MULTIPLY, KeyEvent.KEYCODE_POUND, KeyEvent.KEYCODE_CALL -> {
                if (event.repeatCount == 0) pressedTapKeys.add(keyCode)
                return true
            }
            KeyEvent.KEYCODE_SOFT_LEFT, KeyEvent.KEYCODE_SOFT_RIGHT,
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_CAMERA, LauncherPrefs.KEYCODE_CAMERA_ALT, LauncherPrefs.KEYCODE_CAMERA_ALT2,
            KeyEvent.KEYCODE_F4, KEYCODE_ASSISTANT_RAW -> {
                beginPressTracking(keyCode, event)
                if (event.repeatCount == 0) scheduleAssign(keyCode)
                return true
            }
            KeyEvent.KEYCODE_MENU -> {
                beginPressTracking(keyCode, event)
                if (event.repeatCount == 0) scheduleAssign(keyCode)
                trackLongPress(keyCode, event)
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                beginPressTracking(keyCode, event)
                if (event.repeatCount == 0) scheduleAssign(keyCode)
                trackLongPress(keyCode, event)
            }
            // Diagnostic aid for identifying vendor-specific physical buttons
            // (e.g. on Kyocera-style hardware) that don't map to a keycode
            // this app already recognizes above.
            else -> toastIfUnknownKey(keyCode, event)
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> {
                cancelDigitHold(keyCode)
                // The long-press action (if any) already fired from the scheduled runnable.
                if (longPressFired.remove(keyCode)) {
                    digitHandledDownTime[keyCode] = event.downTime
                    return true
                }
                // Some hardware delivers a spurious extra ACTION_UP mid-hold (see
                // digitHandledDownTime's doc) - a second UP sharing a downTime we've
                // already resolved is that spurious event, not a real new release.
                if (digitHandledDownTime[keyCode] == event.downTime) return true
                digitHandledDownTime[keyCode] = event.downTime
                scheduleDigitTap(keyCode)
                return true
            }
            KeyEvent.KEYCODE_STAR, KeyEvent.KEYCODE_NUMPAD_MULTIPLY, KeyEvent.KEYCODE_POUND, KeyEvent.KEYCODE_CALL -> {
                if (pressedTapKeys.remove(keyCode) && !event.isCanceled) {
                    when {
                        keyCode == KeyEvent.KEYCODE_CALL -> openCallLog()
                        isStarKey(keyCode) -> startDial("*")
                        else -> startDial("#")
                    }
                }
                return true
            }
            KeyEvent.KEYCODE_SOFT_LEFT -> {
                cancelAssign(keyCode)
                if (assignFired.remove(keyCode)) return true
                openLeftKeyApp()
                return true
            }
            KeyEvent.KEYCODE_SOFT_RIGHT -> {
                cancelAssign(keyCode)
                if (assignFired.remove(keyCode)) return true
                openRightKeyApp()
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_CAMERA, LauncherPrefs.KEYCODE_CAMERA_ALT, LauncherPrefs.KEYCODE_CAMERA_ALT2,
            KeyEvent.KEYCODE_F4, KEYCODE_ASSISTANT_RAW -> {
                cancelAssign(keyCode)
                if (assignFired.remove(keyCode)) return true
                launchDirectionalKeyApp(keyCode)
                return true
            }
            KeyEvent.KEYCODE_MENU -> {
                cancelAssign(keyCode)
                if (assignFired.remove(keyCode)) return true
                if (longPressFired.remove(keyCode)) launchMenuKeyApp() else openAppDrawer()
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                cancelAssign(keyCode)
                if (assignFired.remove(keyCode)) return true
                if (longPressFired.remove(keyCode)) {
                    launchBackLongPressApp()
                    return true
                }
                // Short tap: fall through to super, whose default BACK handling
                // fires onBackPressedDispatcher (the OnBackPressedCallback above
                // opens the app drawer), exactly as before.
            }
        }
        return super.onKeyUp(keyCode, event)
    }

    /** Starts long-press tracking on the initial down and clears any stale hold state from a prior, interrupted press. */
    private fun beginPressTracking(keyCode: Int, event: KeyEvent) {
        if (event.repeatCount != 0) return
        event.startTracking()
        assignFired.remove(keyCode)
        longPressFired.remove(keyCode)
    }

    /** Records that [keyCode] has crossed the framework long-press threshold, unless its 5-second assign already fired. */
    private fun trackLongPress(keyCode: Int, event: KeyEvent) {
        if (event.isLongPress && keyCode !in assignFired) longPressFired.add(keyCode)
    }

    /** Schedules [keyCode]'s assign menu to open after a 5-second hold; cancelled by [cancelAssign] on release. */
    private fun scheduleAssign(keyCode: Int) {
        cancelAssign(keyCode)
        val runnable = Runnable {
            assignFired.add(keyCode)
            openAssignMenu(keyCode)
        }
        assignRunnables[keyCode] = runnable
        assignHandler.postDelayed(runnable, ASSIGN_HOLD_MS)
    }

    private fun cancelAssign(keyCode: Int) {
        assignRunnables.remove(keyCode)?.let { assignHandler.removeCallbacks(it) }
    }

    /**
     * Schedules [keyCode]'s speed-dial/voicemail action to fire after a
     * long-press hold; cancelled by [cancelDigitHold] on release. Timed
     * ourselves (rather than trusting [KeyEvent.isLongPress]) since that
     * flag's delivery has proven unreliable on some hardware, firing both
     * the short-tap dial and the long-press action for the same press.
     */
    private fun scheduleDigitHold(keyCode: Int) {
        cancelDigitHold(keyCode)
        val runnable = Runnable {
            longPressFired.add(keyCode)
            if (keyCode == KeyEvent.KEYCODE_1) callVoicemail() else dialSpeedDial(keyCode - KeyEvent.KEYCODE_0)
        }
        digitHoldRunnables[keyCode] = runnable
        assignHandler.postDelayed(runnable, DIGIT_HOLD_MS)
    }

    /**
     * Opens an outer button's assigned app on whichever half of the press
     * arrives first (once per press, keyed by [KeyEvent.getDownTime]) - only
     * unless [outerKeyBlockReason] says not to. Both halves are consumed either way.
     */
    private fun onOuterKeyEvent(keyCode: Int, event: KeyEvent) {
        if (event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP) return
        if (extraKeyFiredDownTime[keyCode] == event.downTime) return
        extraKeyFiredDownTime[keyCode] = event.downTime
        val blocked = outerKeyBlockReason()
        if (blocked == null) {
            launchDirectionalKeyApp(keyCode)
        } else if (blocked != 0) {
            // Say why on screen (the user has no logcat); a screen-off press
            // stays silent - nobody's looking, and it's the pocket case.
            Toast.makeText(this, blocked, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Why an outer button may not launch anything right now, as a message
     * string res (0 = screen off, stay silent), or null if it may. Blocks a
     * press when the screen is off (a closed flip turns the main screen off
     * too, so this is also the flip check - Android's keyboard-hidden flag
     * isn't reliable on these keypads), on the lock screen, or when Home
     * isn't the window in front.
     */
    private fun outerKeyBlockReason(): Int? {
        val power = getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (power != null && !power.isInteractive) return 0
        val keyguard = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        if (keyguard != null && keyguard.isKeyguardLocked) return R.string.outer_key_blocked_locked
        if (!hasWindowFocus()) return R.string.outer_key_blocked_focus
        return null
    }

    private fun cancelDigitHold(keyCode: Int) {
        digitHoldRunnables.remove(keyCode)?.let { assignHandler.removeCallbacks(it) }
    }

    /**
     * Schedules [keyCode]'s short-tap dial to fire after a brief debounce
     * window, cancelled by [cancelDigitTap] if a new press for the same key
     * arrives first - this hardware appears to deliver one long hold as two
     * separate press/release cycles, so an immediate release-fires-dial
     * would double-dial (tap action + the real long-press action both firing).
     */
    private fun scheduleDigitTap(keyCode: Int) {
        cancelDigitTap(keyCode)
        val runnable = Runnable {
            startDial((keyCode - KeyEvent.KEYCODE_0).toString())
        }
        digitTapRunnables[keyCode] = runnable
        assignHandler.postDelayed(runnable, DIGIT_TAP_DEBOUNCE_MS)
    }

    private fun cancelDigitTap(keyCode: Int) {
        digitTapRunnables.remove(keyCode)?.let { assignHandler.removeCallbacks(it) }
    }

    /** Opens the app picker to assign [keyCode] (MENU/BACK/soft-key/D-pad/Camera - digits are handled by the phone's own Speed Dial settings, see [dialSpeedDial]). */
    private fun openAssignMenu(keyCode: Int) {
        pendingAssignAppKey = keyCode
        assignAppLauncher.launch(Intent(this, AppPickerActivity::class.java))
    }

    /**
     * Dials [digit]'s speed-dial number: the phone's own dialer data first,
     * then the launcher's own slot (see [SpeedDialSettingsActivity]).
     */
    private fun dialSpeedDial(digit: Int) {
        contactsPermission.run(onDenied = { resolveSpeedDial(digit, SystemSpeedDialResult.Unsupported) }) {
            resolveSpeedDial(digit, systemSpeedDial(this, digit))
        }
    }

    /**
     * Calls whichever number is set, or - if neither is - opens the screen
     * whose slots this key can actually read: the phone's own Speed Dial
     * settings when its data is readable (the E4810), else the launcher's
     * (the E4610, whose dialer data this app can't see).
     */
    private fun resolveSpeedDial(digit: Int, system: SystemSpeedDialResult) {
        val number = (system as? SystemSpeedDialResult.Found)?.entry?.number ?: prefs.getSpeedDial(digit)?.number
        if (number != null) {
            placeCall(callPermission, number)
            return
        }
        Toast.makeText(this, getString(R.string.speed_dial_unset_shortcut_toast, digit), Toast.LENGTH_SHORT).show()
        if (system is SystemSpeedDialResult.Unset) {
            openSystemSpeedDial()
        } else {
            startActivity(
                Intent(this, SpeedDialSettingsActivity::class.java)
                    .putExtra(SpeedDialSettingsActivity.EXTRA_FOCUS_DIGIT, digit),
            )
        }
    }

    /**
     * Launches [keyCode]'s bound app immediately, or - if unset - falls back to
     * the default camera app for Camera or the voice assistant for
     * Mic/Assistant, or toasts and jumps to Home Shortcuts settings for
     * every other key (and for Mic when no assistant is installed).
     */
    private fun launchDirectionalKeyApp(keyCode: Int) {
        val key = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> prefs.getDpadUpApp()
            KeyEvent.KEYCODE_DPAD_DOWN -> prefs.getDpadDownApp()
            KeyEvent.KEYCODE_DPAD_LEFT -> prefs.getDpadLeftApp()
            KeyEvent.KEYCODE_DPAD_RIGHT -> prefs.getDpadRightApp()
            KeyEvent.KEYCODE_CAMERA, LauncherPrefs.KEYCODE_CAMERA_ALT, LauncherPrefs.KEYCODE_CAMERA_ALT2 -> prefs.getCameraKeyApp()
            KeyEvent.KEYCODE_F4, KEYCODE_ASSISTANT_RAW -> prefs.getAssistantKeyApp()
            in LauncherPrefs.EXTRA_KEYCODES -> prefs.getExtraKeyApp(keyCode)
            else -> null
        }
        if (key != null) {
            launchAppByKey(key)
            return
        }
        if (keyCode == KeyEvent.KEYCODE_CAMERA || keyCode == LauncherPrefs.KEYCODE_CAMERA_ALT || keyCode == LauncherPrefs.KEYCODE_CAMERA_ALT2) {
            openDefaultCamera()
            return
        }
        if ((keyCode == KeyEvent.KEYCODE_F4 || keyCode == KEYCODE_ASSISTANT_RAW) && openVoiceAssistant()) return
        Toast.makeText(this, R.string.directional_key_unset_toast, Toast.LENGTH_SHORT).show()
        val isDpad = keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
            keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
        openKeySettings(if (isDpad) HomeKeysSettingsActivity.GROUP_NAVIGATION else HomeKeysSettingsActivity.GROUP_OTHER)
    }

    /** Opens the Home Keys settings screen holding an unassigned key's row. */
    private fun openKeySettings(group: String) = startActivity(
        Intent(this, HomeKeysSettingsActivity::class.java).putExtra(HomeKeysSettingsActivity.EXTRA_GROUP, group),
    )

    /**
     * Opens the phone's voice assistant / voice command app for an
     * unassigned Mic/Assistant key. Returns false when nothing handles any
     * of the usual intents, so the caller can fall back.
     */
    private fun openVoiceAssistant(): Boolean {
        val intents = listOf(
            Intent(Intent.ACTION_VOICE_COMMAND),
            Intent(Intent.ACTION_ASSIST),
            Intent(RecognizerIntent.ACTION_VOICE_SEARCH_HANDS_FREE),
        )
        for (intent in intents) {
            try {
                startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            } catch (e: Exception) {
                // Try the next one.
            }
        }
        return false
    }

    private fun openDefaultCamera() {
        try {
            startActivity(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))
        } catch (e: Exception) {
            Toast.makeText(this, R.string.toast_not_available, Toast.LENGTH_SHORT).show()
        }
    }

    /** Refreshes the D-pad shortcut pod's four icons; the pod itself always stays visible (it also houses the OK button). */
    private fun refreshDirectionalPod() {
        bindKeyIcon(dpadIconUp, prefs.getDpadUpApp())
        bindKeyIcon(dpadIconDown, prefs.getDpadDownApp())
        bindKeyIcon(dpadIconLeft, prefs.getDpadLeftApp())
        bindKeyIcon(dpadIconRight, prefs.getDpadRightApp())
    }

    /** Binds [key]'s squircle-masked icon into [view] and shows it, or hides [view] when [key] is null. Returns whether it was bound. */
    private fun bindKeyIcon(view: ImageView, key: String?): Boolean {
        val icon = key?.let { AppRepository.resolveRawIcon(this, it) }
        if (icon == null) {
            view.visibility = View.GONE
            return false
        }
        view.setImageDrawable(
            IconShapeRenderer.render(
                context = this,
                source = icon,
                shape = LauncherPrefs.IconShape.SQUIRCLE,
                wrapEnabled = true,
                legacyBackgroundEnabled = true,
            ),
        )
        view.visibility = View.VISIBLE
        return true
    }

    private fun callVoicemail() {
        callPermission.run(onDenied = {
            Toast.makeText(this, R.string.toast_no_dialer, Toast.LENGTH_SHORT).show()
        }) {
            try {
                startActivity(Intent(Intent.ACTION_CALL, Uri.parse("voicemail:")))
            } catch (e: Exception) {
                Toast.makeText(this, R.string.toast_no_dialer, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun launchMenuKeyApp() {
        val key = prefs.getMenuKeyApp()
        if (key == null) {
            Toast.makeText(this, R.string.menu_key_unset_toast, Toast.LENGTH_SHORT).show()
        } else {
            launchAppByKey(key)
        }
    }

    private fun launchBackLongPressApp() {
        val key = prefs.getBackLongPressApp()
        if (key == null) {
            Toast.makeText(this, R.string.back_longpress_unset_toast, Toast.LENGTH_SHORT).show()
        } else {
            launchAppByKey(key)
        }
    }

    // ------------------------------------------------------ Default launcher

    /** The startup prompt currently on screen, so a resume never stacks a second one. */
    private var startupPrompt: AlertDialog? = null

    /**
     * At most once per launcher start each, one at a time: ask to make this
     * the default Home app if it isn't, then to turn on Accessibility if
     * it's off (it feeds the Home banner, icon dots and read-aloud on these
     * phones). Both hand off to the phone's own settings screen with a toast
     * naming the D-pad path.
     */
    private fun maybeShowStartupPrompts() {
        if (isFinishing || startupPrompt?.isShowing == true) return
        if (!defaultPromptShown && !isDefaultLauncher()) {
            defaultPromptShown = true
            showStartupPrompt(
                R.string.prompt_default_title,
                R.string.prompt_default_message,
            ) { openSettingsWithPath(Settings.ACTION_HOME_SETTINGS, R.string.path_default_launcher) }
            return
        }
        if (!accessibilityPromptShown && !isAccessibilityServiceEnabled()) {
            accessibilityPromptShown = true
            showStartupPrompt(
                R.string.prompt_accessibility_title,
                R.string.prompt_accessibility_message,
            ) { openSettingsWithPath(Settings.ACTION_ACCESSIBILITY_SETTINGS, R.string.path_accessibility) }
        }
    }

    private fun showStartupPrompt(title: Int, message: Int, onOpen: () -> Unit) {
        var opened = false
        startupPrompt = AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(R.string.prompt_open_settings) { _, _ ->
                opened = true
                onOpen()
            }
            .setNegativeButton(R.string.prompt_not_now, null)
            .setOnDismissListener {
                startupPrompt = null
                // "Not now" moves straight on to the next prompt, if any;
                // after "Open Settings" it waits for the return to Home
                // (onResume), so it never pops up over the settings screen.
                if (!opened) maybeShowStartupPrompts()
            }
            .show()
    }

    // ---------------------------------------------------------------- Updates

    /**
     * Checks GitHub for a newer release about once a week (Home resumes
     * constantly, so no background scheduler is needed) and, if there is one,
     * asks the user whether to install it. Settings > Advanced has a manual check too.
     */
    private fun maybeCheckForUpdate() {
        val now = System.currentTimeMillis()
        val last = prefs.getLastUpdateCheckAt()
        // A last-check time in the future means the clock was turned back; check anyway.
        if (now - last in 0 until UpdateChecker.CHECK_INTERVAL_MS) return
        // Let the startup prompts finish first; the next resume checks instead.
        if (startupPrompt?.isShowing == true) return
        // Recorded up front so resumes while the check is in flight don't start another.
        prefs.setLastUpdateCheckAt(now)
        updateLoader.load(
            produce = {
                try {
                    Result.success(UpdateChecker.fetchLatest(this))
                } catch (e: Exception) {
                    Result.failure(e)
                }
            },
            consume = { result ->
                result.onFailure {
                    // Offline or rate-limited: try again in a day rather than a week.
                    prefs.setLastUpdateCheckAt(
                        now - UpdateChecker.CHECK_INTERVAL_MS + UpdateChecker.RETRY_AFTER_FAILURE_MS,
                    )
                }
                val release = result.getOrNull() ?: return@load
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                    startupPrompt?.isShowing != true
                ) {
                    showUpdatePrompt(release, updateLoader)
                } else {
                    // Home was left (or a startup prompt came up) mid-check; ask on the next resume instead.
                    prefs.setLastUpdateCheckAt(0L)
                }
            },
        )
    }

    companion object {
        // Shown at most once per process so we don't nag on every resume.
        private var defaultPromptShown = false
        private var accessibilityPromptShown = false

        /** How long an assignable key must be held to open its assign menu. */
        private const val ASSIGN_HOLD_MS = 5000L

        /** How long a low-priority ("Other") notification banner stays up before auto-hiding itself. */
        private const val OTHER_NOTIF_AUTO_HIDE_MS = 6000L

        /**
         * How long a digit must be held before its long-press action (speed
         * dial / voicemail) fires - mirrors the framework's own long-press
         * threshold, just timed by us (see [scheduleDigitHold]).
         */
        private val DIGIT_HOLD_MS = ViewConfiguration.getLongPressTimeout().toLong()

        /**
         * Grace window after a digit's release before its short-tap dial
         * actually fires; long enough to bridge the gap between this
         * hardware's two synthetic press/release cycles for one long hold
         * (see [scheduleDigitTap]), short enough to be imperceptible for a
         * genuine single tap.
         */
        private const val DIGIT_TAP_DEBOUNCE_MS = 200L

        /** Captured in [dispatchKeyEvent], before default focus-search can consume them. */
        private val DIRECTIONAL_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_CAMERA,
            LauncherPrefs.KEYCODE_CAMERA_ALT,
            LauncherPrefs.KEYCODE_CAMERA_ALT2,
        )

        /**
         * The Kyocera dialer's own call log, tried in order by [openCallLog]:
         * `.calllog.CallLogActivityKc` is the E4610's (confirmed via logcat),
         * `.app.calllog.CallLogActivityKc` the newer models'.
         */
        private val SYSTEM_CALL_LOG_COMPONENTS = listOf(
            ComponentName("com.android.dialer", "com.android.dialer.calllog.CallLogActivityKc"),
            ComponentName("com.android.dialer", "com.android.dialer.app.calllog.CallLogActivityKc"),
        )
    }
}
