package com.flipos.launcher.activities

import com.flipos.launcher.R

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.provider.MediaStore
import android.view.KeyEvent
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.flipos.launcher.data.AppRepository
import com.flipos.launcher.data.IconShapeRenderer
import com.flipos.launcher.data.LauncherPrefs
import com.flipos.launcher.data.NotificationCounts
import com.flipos.launcher.util.BackgroundLoader
import com.flipos.launcher.util.PermissionGate
import com.flipos.launcher.util.accentColorAlpha
import com.flipos.launcher.util.launchAppByKey
import com.flipos.launcher.util.placeCall
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The KaiOS-style home screen:
 *  - a large clock + date, and
 *  - "Notifications · apps · Contacts" soft keys along the bottom, with a
 *    D-pad/Camera shortcut icon pod nested between the two labels and the
 *    App-Drawer/OK button sitting in its center.
 *
 * Typing a digit - or `*` / `#` - anywhere on Home opens the phone dialer
 * prefilled with it. The center button opens All Apps; long-pressing it (via
 * touch) opens Settings.
 *
 * Every physical key (digits 0/2-9, MENU, BACK, the soft keys, D-pad
 * Up/Down/Left/Right, Camera, and four vendor-specific extra buttons
 * identified by scan code) also carries a long-press action and a
 * 5-second-hold "assign" menu - see the Key handling section below. Digits
 * are the exception: their long-press (speed dial / voicemail) fires the
 * instant it's detected, and assignment for them is Settings-only.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var prefs: LauncherPrefs
    private lateinit var appMenuButton: ImageView
    private lateinit var clock: TextView
    private lateinit var ampm: TextView
    private lateinit var weekday: TextView
    private lateinit var dateLine: TextView
    private lateinit var notifCallsGroup: View
    private lateinit var notifCallsCount: TextView
    private lateinit var notifMessagesGroup: View
    private lateinit var notifMessagesCount: TextView
    private lateinit var notifOtherGroup: View
    private lateinit var notifOtherCount: TextView
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

    /** Scheduled 5-second assign runnables, keyed by keyCode, so a release can cancel them. */
    private val assignRunnables = HashMap<Int, Runnable>()

    /** Keycodes whose 5-second assign menu already fired for the current press. */
    private val assignFired = HashSet<Int>()

    /** Keycodes that have crossed the framework long-press threshold for the current press. */
    private val longPressFired = HashSet<Int>()

    private val callPermission = PermissionGate(this, Manifest.permission.CALL_PHONE)

    /** App picker for MENU/BACK/soft-key/D-pad/Camera/extra-key assignment, writing back based on [pendingAssignAppKey]. */
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
                KeyEvent.KEYCODE_CAMERA, LauncherPrefs.KEYCODE_CAMERA_ALT -> prefs.setCameraKeyApp(key)
                LauncherPrefs.KEYCODE_EXTRA_1 -> prefs.setExtraKey1App(key)
                LauncherPrefs.KEYCODE_EXTRA_2 -> prefs.setExtraKey2App(key)
                LauncherPrefs.KEYCODE_EXTRA_3 -> prefs.setExtraKey3App(key)
                LauncherPrefs.KEYCODE_EXTRA_4 -> prefs.setExtraKey4App(key)
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
        runOnUiThread { updateNotifSummary() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = LauncherPrefs(this)
        val accent = prefs.getAccentColor()
        appliedAccentColor = accent
        if (accent.themeOverlayRes != 0) theme.applyStyle(accent.themeOverlayRes, true)
        if (!prefs.isAnimationsEnabled()) {
            theme.applyStyle(R.style.ThemeOverlay_FlipLauncher_NoAnimations, true)
        }
        setContentView(R.layout.activity_main)

        clock = findViewById(R.id.clock)
        ampm = findViewById(R.id.ampm)
        weekday = findViewById(R.id.weekday)
        dateLine = findViewById(R.id.date_line)
        notifCallsGroup = findViewById(R.id.notif_calls_group)
        notifCallsCount = findViewById(R.id.notif_calls_count)
        notifMessagesGroup = findViewById(R.id.notif_messages_group)
        notifMessagesCount = findViewById(R.id.notif_messages_count)
        notifOtherGroup = findViewById(R.id.notif_other_group)
        notifOtherCount = findViewById(R.id.notif_other_count)
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
        NotificationCounts.addListener(notifListener)
        updateNotifSummary()
        maybePromptDefaultLauncher()
        // The accent color may have changed in Settings while Home was backgrounded;
        // theme overlays only apply at onCreate, so recreate to pick it up. Done last
        // (after registering the receiver/listener above) so onPause's matching
        // unregister calls below still have something to unregister.
        if (prefs.getAccentColor() != appliedAccentColor) recreate()
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(timeReceiver)
        unregisterReceiver(packageReceiver)
        NotificationCounts.removeListener(notifListener)
        // A key hold that's interrupted mid-press (screen off, app switch) may
        // never deliver a matching key-up; drop any scheduled assign timers so
        // they don't fire into the background.
        assignHandler.removeCallbacksAndMessages(null)
        assignRunnables.clear()
    }

    override fun onDestroy() {
        loader.cancel()
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
        if (key != null) launchAppByKey(key) else openNotifications()
    }

    private fun openOptions() = startActivity(Intent(this, SettingsActivity::class.java))

    private fun openRightKeyApp() {
        val key = prefs.getRightKeyApp()
        if (key != null) {
            launchAppByKey(key)
            return
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, ContactsContract.Contacts.CONTENT_URI))
        } catch (e: Exception) {
            try {
                startActivity(Intent(Intent.ACTION_DIAL))
            } catch (ignored: Exception) {
                Toast.makeText(this, R.string.toast_no_contacts, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateNotifSummary() {
        bindNotifBadge(notifCallsGroup, notifCallsCount, prefs.isCallBadgeEnabled(), NotificationCounts.calls, R.string.cd_notif_calls)
        bindNotifBadge(notifMessagesGroup, notifMessagesCount, prefs.isMessageBadgeEnabled(), NotificationCounts.messages, R.string.cd_notif_messages)
        bindNotifBadge(notifOtherGroup, notifOtherCount, prefs.isOtherBadgeEnabled(), NotificationCounts.other, R.string.cd_notif_other)
    }

    private fun bindNotifBadge(group: View, countView: TextView, enabled: Boolean, count: Int, cdRes: Int) {
        if (enabled && count > 0) {
            countView.text = if (count > 99) "99+" else count.toString()
            group.visibility = View.VISIBLE
            // Digits alone aren't meaningful to a screen reader; announce the category.
            group.contentDescription = "$count ${getString(cdRes)}"
        } else {
            group.visibility = View.GONE
        }
    }

    private fun refreshRightKeyLabel() {
        val key = prefs.getRightKeyApp()
        val label = key?.let { AppRepository.resolveComponent(this, it)?.label }
            ?: getString(R.string.softkey_contacts)
        findViewById<TextView>(R.id.softkey_right).text = label
    }

    private fun refreshLeftKeyLabel() {
        val key = prefs.getLeftKeyApp()
        val label = key?.let { AppRepository.resolveComponent(this, it)?.label }
            ?: getString(R.string.softkey_notifications)
        findViewById<TextView>(R.id.softkey_left).text = label
    }

    /** The KaiOS "Notices" action: our own list screen, not the system shade. */
    private fun openNotifications() = startActivity(Intent(this, NoticesActivity::class.java))

    private fun openCallLog() = startActivity(Intent(this, CallLogActivity::class.java))

    // ----------------------------------------------------------- Key handling
    //
    // Digits 1-9/0 fire their long-press action (dial a speed-dial number,
    // dial voicemail) the instant the framework's long-press threshold
    // crosses, like a real feature phone - see the digit branches below.
    // Assignment for digits is Settings-only now (SpeedDialSettingsActivity);
    // there's no in-place hold for them.
    //
    // Every other assignable key (MENU, BACK, the soft keys, D-pad, Camera,
    // the four extra buttons) keeps the original model: swallowed on key-down, resolved on key-up -
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
    // The four "extra" buttons (see EXTRA_KEY_SCAN_CODES) have no reliable
    // KeyEvent.KEYCODE_* of their own, so they're identified by raw scan code
    // and remapped to an app-defined synthetic keycode before being dispatched
    // to onKeyDown/onKeyUp - which otherwise only ever operate on the keyCode
    // *parameter*, never event.keyCode directly, so a synthetic value flows
    // through the existing hold-tracking machinery unchanged.

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val synthetic = EXTRA_KEY_SCAN_CODES[event.scanCode]
        if (synthetic != null) {
            return when (event.action) {
                KeyEvent.ACTION_DOWN -> onKeyDown(synthetic, event)
                KeyEvent.ACTION_UP -> onKeyUp(synthetic, event)
                else -> super.dispatchKeyEvent(event)
            }
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
                beginPressTracking(keyCode, event)
                if (event.isLongPress && keyCode !in longPressFired) {
                    longPressFired.add(keyCode)
                    if (keyCode == KeyEvent.KEYCODE_1) callVoicemail() else dialSpeedDial(keyCode - KeyEvent.KEYCODE_0)
                }
                return true
            }
            KeyEvent.KEYCODE_STAR, KeyEvent.KEYCODE_POUND -> return true
            KeyEvent.KEYCODE_SOFT_LEFT, KeyEvent.KEYCODE_SOFT_RIGHT,
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_CAMERA, LauncherPrefs.KEYCODE_CAMERA_ALT,
            LauncherPrefs.KEYCODE_EXTRA_1, LauncherPrefs.KEYCODE_EXTRA_2,
            LauncherPrefs.KEYCODE_EXTRA_3, LauncherPrefs.KEYCODE_EXTRA_4 -> {
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
            KeyEvent.KEYCODE_CALL -> { openCallLog(); return true }
            KeyEvent.KEYCODE_BACK -> {
                beginPressTracking(keyCode, event)
                if (event.repeatCount == 0) scheduleAssign(keyCode)
                trackLongPress(keyCode, event)
            }
            else -> {
                // Diagnostic aid for identifying vendor-specific physical buttons
                // (e.g. on Kyocera-style hardware) that don't map to a keycode
                // this app already recognizes above.
                if (event.repeatCount == 0 && keyCode !in SILENT_UNKNOWN_KEYS) {
                    Toast.makeText(
                        this,
                        getString(R.string.unrecognized_key_toast, keyCode, event.scanCode),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> {
                // The long-press action (if any) already fired in onKeyDown.
                if (longPressFired.remove(keyCode)) return true
                startDial((keyCode - KeyEvent.KEYCODE_0).toString())
                return true
            }
            KeyEvent.KEYCODE_STAR -> { startDial("*"); return true }
            KeyEvent.KEYCODE_POUND -> { startDial("#"); return true }
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
            KeyEvent.KEYCODE_CAMERA, LauncherPrefs.KEYCODE_CAMERA_ALT,
            LauncherPrefs.KEYCODE_EXTRA_1, LauncherPrefs.KEYCODE_EXTRA_2,
            LauncherPrefs.KEYCODE_EXTRA_3, LauncherPrefs.KEYCODE_EXTRA_4 -> {
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

    /** Opens the app picker to assign [keyCode] (MENU/BACK/soft-key/D-pad/Camera/extra-key - digits are Settings-only, see [dialSpeedDial]). */
    private fun openAssignMenu(keyCode: Int) {
        pendingAssignAppKey = keyCode
        assignAppLauncher.launch(Intent(this, AppPickerActivity::class.java))
    }

    /** Dials [digit]'s speed-dial number immediately, or - if unset - jumps straight to Speed Dial settings on that row. */
    private fun dialSpeedDial(digit: Int) {
        val entry = prefs.getSpeedDial(digit)
        if (entry == null) {
            Toast.makeText(this, getString(R.string.speed_dial_unset_shortcut_toast, digit), Toast.LENGTH_SHORT).show()
            startActivity(
                Intent(this, SpeedDialSettingsActivity::class.java)
                    .putExtra(SpeedDialSettingsActivity.EXTRA_FOCUS_DIGIT, digit),
            )
        } else {
            placeCall(callPermission, entry.number)
        }
    }

    /**
     * Launches [keyCode]'s bound app immediately, or - if unset - falls back to
     * the default camera app for Camera, or toasts and jumps to Home Screen &
     * Keys settings for every other key.
     */
    private fun launchDirectionalKeyApp(keyCode: Int) {
        val key = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> prefs.getDpadUpApp()
            KeyEvent.KEYCODE_DPAD_DOWN -> prefs.getDpadDownApp()
            KeyEvent.KEYCODE_DPAD_LEFT -> prefs.getDpadLeftApp()
            KeyEvent.KEYCODE_DPAD_RIGHT -> prefs.getDpadRightApp()
            KeyEvent.KEYCODE_CAMERA, LauncherPrefs.KEYCODE_CAMERA_ALT -> prefs.getCameraKeyApp()
            LauncherPrefs.KEYCODE_EXTRA_1 -> prefs.getExtraKey1App()
            LauncherPrefs.KEYCODE_EXTRA_2 -> prefs.getExtraKey2App()
            LauncherPrefs.KEYCODE_EXTRA_3 -> prefs.getExtraKey3App()
            LauncherPrefs.KEYCODE_EXTRA_4 -> prefs.getExtraKey4App()
            else -> null
        }
        if (key != null) {
            launchAppByKey(key)
            return
        }
        if (keyCode == KeyEvent.KEYCODE_CAMERA || keyCode == LauncherPrefs.KEYCODE_CAMERA_ALT) {
            openDefaultCamera()
            return
        }
        Toast.makeText(this, R.string.directional_key_unset_toast, Toast.LENGTH_SHORT).show()
        startActivity(Intent(this, HomeKeysSettingsActivity::class.java))
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
        bindPodIcon(dpadIconUp, prefs.getDpadUpApp())
        bindPodIcon(dpadIconDown, prefs.getDpadDownApp())
        bindPodIcon(dpadIconLeft, prefs.getDpadLeftApp())
        bindPodIcon(dpadIconRight, prefs.getDpadRightApp())
    }

    /** Binds [key]'s squircle-masked icon into [view] and shows it, or hides [view] when [key] is null. Returns whether it was bound. */
    private fun bindPodIcon(view: ImageView, key: String?): Boolean {
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
                legacyBackgroundEnabled = prefs.isLegacyIconBackgroundEnabled(),
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

    private fun isDefaultLauncher(): Boolean {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolved = packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        return resolved?.activityInfo?.packageName == packageName
    }

    private fun maybePromptDefaultLauncher() {
        // Gently hint, but never yank the user away — they opt in deliberately via
        // Options → "Set as Default Launcher".
        if (defaultPromptShown || isDefaultLauncher()) return
        defaultPromptShown = true
        Toast.makeText(this, R.string.choose_home_app, Toast.LENGTH_LONG).show()
    }

    companion object {
        // Shown at most once per process so we don't nag on every resume.
        private var defaultPromptShown = false

        /** How long an assignable key must be held to open its assign menu. */
        private const val ASSIGN_HOLD_MS = 5000L

        /** Captured in [dispatchKeyEvent], before default focus-search can consume them. */
        private val DIRECTIONAL_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_CAMERA,
            LauncherPrefs.KEYCODE_CAMERA_ALT,
        )

        /**
         * Four vendor-specific buttons with no reliable KeyEvent.KEYCODE_* of
         * their own, identified instead by raw scan code and remapped to an
         * app-defined synthetic keycode in [dispatchKeyEvent].
         */
        private val EXTRA_KEY_SCAN_CODES = mapOf(
            763 to LauncherPrefs.KEYCODE_EXTRA_1,
            764 to LauncherPrefs.KEYCODE_EXTRA_2,
            765 to LauncherPrefs.KEYCODE_EXTRA_3,
            766 to LauncherPrefs.KEYCODE_EXTRA_4,
        )

        /** Keys that should never trigger the unrecognized-key diagnostic toast. */
        private val SILENT_UNKNOWN_KEYS = setOf(
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_VOLUME_MUTE,
            KeyEvent.KEYCODE_POWER,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
        )
    }
}
