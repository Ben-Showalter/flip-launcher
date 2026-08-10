package com.flipos.launcher.activities

import com.flipos.launcher.R

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.widget.Toast
import com.flipos.launcher.ui.ListRowAdapter
import com.flipos.launcher.ui.Row

/**
 * The launcher's single, unified settings hub, reached from the Home screen
 * (long-press the center key) and also listed in the app drawer. Groups every
 * launcher preference into a few clear categories that drill into their own
 * screens, plus quick access to the relevant system screens.
 */
class SettingsActivity : BaseListActivity() {

    private lateinit var adapter: ListRowAdapter
    private val actions = HashMap<String, () -> Unit>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        titleView.text = getString(R.string.title_settings)

        actions[ID_APPEARANCE] = { open(AppearanceSettingsActivity::class.java) }
        actions[ID_NOTIFICATIONS] = { open(NotificationSettingsActivity::class.java) }
        actions[ID_HOME_KEYS] = { open(HomeKeysSettingsActivity::class.java) }
        actions[ID_APPS_DRAWER] = { open(AppsSettingsActivity::class.java) }
        actions[ID_DEFAULT_LAUNCHER] = { startSafely(Intent(Settings.ACTION_HOME_SETTINGS)) }
        actions[ID_SYSTEM_SETTINGS] = { startSafely(Intent(Settings.ACTION_SETTINGS)) }

        adapter = ListRowAdapter(onClick = { dispatch(it) })
        listView.adapter = adapter
        adapter.submit(buildRows())

        softKeys.setLabels(
            getString(R.string.softkey_back),
            getString(R.string.softkey_select),
            null,
        )
        softKeys.setOnLeftClick { finish() }
        softKeys.setOnCenterClick { focusedPosition().takeIf { it >= 0 }?.let { dispatch(it) } }
        focusFirst()
    }

    private fun buildRows(): List<Row> = listOf(
        Row.section(getString(R.string.sec_personalize)),
        Row(
            id = ID_APPEARANCE,
            title = getString(R.string.cat_appearance),
            subtitle = getString(R.string.cat_appearance_sub),
            chevron = true,
        ),
        Row(
            id = ID_NOTIFICATIONS,
            title = getString(R.string.cat_notifications),
            subtitle = getString(R.string.cat_notifications_sub),
            chevron = true,
        ),
        Row.section(getString(R.string.sec_home_apps)),
        Row(
            id = ID_HOME_KEYS,
            title = getString(R.string.cat_home_keys),
            subtitle = getString(R.string.cat_home_keys_sub),
            chevron = true,
        ),
        Row(
            id = ID_APPS_DRAWER,
            title = getString(R.string.cat_apps_drawer),
            subtitle = getString(R.string.cat_apps_drawer_sub),
            chevron = true,
        ),
        Row.section(getString(R.string.sec_system)),
        Row(id = ID_DEFAULT_LAUNCHER, title = getString(R.string.opt_default_launcher), chevron = true),
        Row(id = ID_SYSTEM_SETTINGS, title = getString(R.string.opt_system_settings), chevron = true),
    )

    private fun dispatch(position: Int) {
        adapter.rowAt(position)?.id?.let { actions[it]?.invoke() }
    }

    private fun open(cls: Class<*>) = startActivity(Intent(this, cls))

    private fun startSafely(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, R.string.toast_not_available, Toast.LENGTH_SHORT).show()
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
        private const val ID_APPEARANCE = "appearance"
        private const val ID_NOTIFICATIONS = "notifications"
        private const val ID_HOME_KEYS = "home_keys"
        private const val ID_APPS_DRAWER = "apps_drawer"
        private const val ID_DEFAULT_LAUNCHER = "default_launcher"
        private const val ID_SYSTEM_SETTINGS = "system_settings"
    }
}
