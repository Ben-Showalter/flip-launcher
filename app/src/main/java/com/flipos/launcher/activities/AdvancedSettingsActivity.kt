package com.flipos.launcher.activities

import com.flipos.launcher.R

import android.Manifest
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import com.flipos.launcher.data.LauncherPrefs
import com.flipos.launcher.data.UpdateChecker
import com.flipos.launcher.ui.ListRowAdapter
import com.flipos.launcher.ui.Row
import com.flipos.launcher.util.BackgroundLoader
import com.flipos.launcher.util.PermissionGate
import com.flipos.launcher.util.isAccessibilityServiceEnabled
import com.flipos.launcher.util.isDefaultLauncher
import com.flipos.launcher.util.isNotificationAccessGranted
import com.flipos.launcher.util.openAppPermissionSettings
import com.flipos.launcher.util.openSettingsWithPath
import com.flipos.launcher.util.openSystemSettings
import com.flipos.launcher.util.requestListenerRebindIfStale
import com.flipos.launcher.util.showUpdatePrompt

/**
 * Advanced: everything that hands off to the phone's own settings (default
 * Home app, Notification/Accessibility/Call Log access, Phone Settings), plus
 * whether opening Settings asks which one and a manual update check - kept
 * out of the everyday categories, like TurboText's Advanced screen.
 */
class AdvancedSettingsActivity : BaseListActivity() {

    private lateinit var prefs: LauncherPrefs
    private lateinit var adapter: ListRowAdapter
    private val actions = HashMap<String, () -> Unit>()
    private val updateLoader = BackgroundLoader()

    private val readCallLogPermission = PermissionGate(this, Manifest.permission.READ_CALL_LOG)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = LauncherPrefs(this)
        titleView.text = getString(R.string.cat_advanced)

        actions[ID_DEFAULT_LAUNCHER] = {
            openSettingsWithPath(Settings.ACTION_HOME_SETTINGS, R.string.path_default_launcher)
        }
        actions[ID_NOTIFICATION_ACCESS] = {
            openSettingsWithPath(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS, R.string.path_notification_access)
        }
        actions[ID_ACCESSIBILITY] = {
            openSettingsWithPath(Settings.ACTION_ACCESSIBILITY_SETTINGS, R.string.path_accessibility)
        }
        actions[ID_CALL_LOG] = { requestCallLogAccess() }
        actions[ID_PHONE_SETTINGS] = { openSystemSettings() }
        actions[ID_SETTINGS_CHOOSER] = {
            prefs.setSettingsChooserEnabled(!prefs.isSettingsChooserEnabled()); refreshRows()
        }
        actions[ID_CHECK_UPDATES] = { checkForUpdate() }

        adapter = ListRowAdapter(onClick = { dispatch(it) })
        listView.adapter = adapter
        softKeys.setLabels(null, getString(R.string.softkey_select), null)
        softKeys.setOnCenterClick { focusedPosition().takeIf { it >= 0 }?.let { dispatch(it) } }
        refreshRows()
        focusFirst()
    }

    override fun onResume() {
        super.onResume()
        if (isRecreatingForAccent) return
        // Access is granted on separate system screens, so re-check on return.
        refreshRows()
        requestListenerRebindIfStale()
    }

    private fun status(granted: Boolean): String =
        getString(if (granted) R.string.settings_notif_access_granted else R.string.settings_notif_access_denied)

    private fun refreshRows() {
        adapter.submit(
            listOf(
                Row(
                    id = ID_DEFAULT_LAUNCHER,
                    title = getString(R.string.opt_default_launcher),
                    trailing = getString(if (isDefaultLauncher()) R.string.settings_default_launcher_yes else R.string.settings_default_launcher_no),
                    chevron = true,
                ),
                Row(
                    id = ID_ACCESSIBILITY,
                    title = getString(R.string.settings_accessibility_access),
                    subtitle = getString(R.string.settings_accessibility_access_sub),
                    trailing = status(isAccessibilityServiceEnabled()),
                    chevron = true,
                ),
                Row(
                    id = ID_NOTIFICATION_ACCESS,
                    title = getString(R.string.settings_notif_access),
                    subtitle = getString(R.string.settings_notif_access_sub),
                    trailing = status(isNotificationAccessGranted()),
                    chevron = true,
                ),
                Row(
                    id = ID_CALL_LOG,
                    title = getString(R.string.settings_calllog_access),
                    subtitle = getString(R.string.settings_calllog_access_sub),
                    trailing = status(readCallLogPermission.isGranted()),
                    chevron = true,
                ),
                Row(
                    id = ID_SETTINGS_CHOOSER,
                    title = getString(R.string.settings_settings_chooser),
                    subtitle = getString(R.string.settings_settings_chooser_sub),
                    toggle = prefs.isSettingsChooserEnabled(),
                ),
                Row(id = ID_PHONE_SETTINGS, title = getString(R.string.settings_phone_settings), chevron = true),
                Row(
                    id = ID_CHECK_UPDATES,
                    title = getString(R.string.opt_check_updates),
                    subtitle = getString(R.string.opt_check_updates_sub, UpdateChecker.installedVersion(this)),
                ),
            ),
        )
    }

    private fun dispatch(position: Int) {
        adapter.rowAt(position)?.id?.let { actions[it]?.invoke() }
    }

    /** Manual counterpart to Home's weekly automatic check (MainActivity.maybeCheckForUpdate). */
    private fun checkForUpdate() {
        Toast.makeText(this, R.string.update_checking, Toast.LENGTH_SHORT).show()
        updateLoader.load(
            produce = {
                try {
                    Result.success(UpdateChecker.fetchLatest(this))
                } catch (e: Exception) {
                    Result.failure(e)
                }
            },
            consume = { result ->
                val release = result.getOrNull()
                when {
                    result.isFailure ->
                        Toast.makeText(this, R.string.update_check_failed, Toast.LENGTH_LONG).show()
                    release == null ->
                        Toast.makeText(this, R.string.update_up_to_date, Toast.LENGTH_SHORT).show()
                    else -> showUpdatePrompt(release, updateLoader)
                }
            },
        )
    }

    override fun onDestroy() {
        updateLoader.cancel()
        super.onDestroy()
    }

    private fun requestCallLogAccess() {
        readCallLogPermission.run(
            onDenied = {
                // shouldShowRequestPermissionRationale is false both before the
                // first ask and after a permanent denial; inside this callback
                // we've just been denied, so false here means permanent - send
                // the user to the app's permission settings instead of a dead end.
                if (!shouldShowRequestPermissionRationale(Manifest.permission.READ_CALL_LOG)) {
                    openAppPermissionSettings()
                }
            },
            action = { refreshRows() },
        )
    }

    companion object {
        private const val ID_DEFAULT_LAUNCHER = "default_launcher"
        private const val ID_ACCESSIBILITY = "accessibility"
        private const val ID_NOTIFICATION_ACCESS = "notification_access"
        private const val ID_CALL_LOG = "call_log"
        private const val ID_SETTINGS_CHOOSER = "settings_chooser"
        private const val ID_PHONE_SETTINGS = "phone_settings"
        private const val ID_CHECK_UPDATES = "check_updates"
    }
}
