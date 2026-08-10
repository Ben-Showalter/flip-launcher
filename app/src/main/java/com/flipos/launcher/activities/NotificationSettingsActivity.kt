package com.flipos.launcher.activities

import com.flipos.launcher.R

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import com.flipos.launcher.data.LauncherPrefs
import com.flipos.launcher.ui.ListRowAdapter
import com.flipos.launcher.ui.Row

/** Notification settings: access grant plus which badges appear on Home/icons. */
class NotificationSettingsActivity : BaseListActivity() {

    private lateinit var prefs: LauncherPrefs
    private lateinit var adapter: ListRowAdapter
    private val actions = HashMap<String, () -> Unit>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = LauncherPrefs(this)
        titleView.text = getString(R.string.cat_notifications)

        actions[ID_ACCESS] = { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        actions[ID_CALLS] = {
            prefs.setCallBadgeEnabled(!prefs.isCallBadgeEnabled()); refreshRows()
        }
        actions[ID_MESSAGES] = {
            prefs.setMessageBadgeEnabled(!prefs.isMessageBadgeEnabled()); refreshRows()
        }
        actions[ID_OTHER] = {
            prefs.setOtherBadgeEnabled(!prefs.isOtherBadgeEnabled()); refreshRows()
        }
        actions[ID_DOTS] = {
            prefs.setIconNotificationDotEnabled(!prefs.isIconNotificationDotEnabled()); refreshRows()
        }

        adapter = ListRowAdapter(onClick = { dispatch(it) })
        listView.adapter = adapter

        softKeys.setLabels(
            getString(R.string.softkey_back),
            getString(R.string.softkey_select),
            null,
        )
        softKeys.setOnLeftClick { finish() }
        softKeys.setOnCenterClick { focusedPosition().takeIf { it >= 0 }?.let { dispatch(it) } }
        refreshRows()
        focusFirst()
    }

    override fun onResume() {
        super.onResume()
        if (isRecreatingForAccent) return
        // Access is granted from a separate system screen, so re-check on return.
        refreshRows()
    }

    private fun refreshRows() {
        val accessTrailing = if (isNotificationAccessGranted()) {
            getString(R.string.settings_notif_access_granted)
        } else {
            getString(R.string.settings_notif_access_denied)
        }
        adapter.submit(
            listOf(
                Row.section(getString(R.string.sec_notif_access)),
                Row(
                    id = ID_ACCESS,
                    title = getString(R.string.settings_notif_access),
                    subtitle = getString(R.string.settings_notif_access_sub),
                    trailing = accessTrailing,
                    chevron = true,
                ),
                Row.section(getString(R.string.sec_notif_home)),
                Row(id = ID_CALLS, title = getString(R.string.settings_notif_calls), toggle = prefs.isCallBadgeEnabled()),
                Row(id = ID_MESSAGES, title = getString(R.string.settings_notif_messages), toggle = prefs.isMessageBadgeEnabled()),
                Row(id = ID_OTHER, title = getString(R.string.settings_notif_other), toggle = prefs.isOtherBadgeEnabled()),
                Row.section(getString(R.string.sec_icons)),
                Row(id = ID_DOTS, title = getString(R.string.settings_notif_icon_dots), toggle = prefs.isIconNotificationDotEnabled()),
            ),
        )
    }

    private fun dispatch(position: Int) {
        adapter.rowAt(position)?.id?.let { actions[it]?.invoke() }
    }

    private fun isNotificationAccessGranted(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        return flat.split(':').any {
            ComponentName.unflattenFromString(it)?.packageName == packageName
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_SOFT_LEFT) {
            finish()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    companion object {
        private const val ID_ACCESS = "access"
        private const val ID_CALLS = "calls"
        private const val ID_MESSAGES = "messages"
        private const val ID_OTHER = "other"
        private const val ID_DOTS = "dots"
    }
}
