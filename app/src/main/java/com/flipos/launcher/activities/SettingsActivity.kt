package com.flipos.launcher.activities

import com.flipos.launcher.R

import android.content.Intent
import android.os.Bundle
import com.flipos.launcher.ui.ListRowAdapter
import com.flipos.launcher.ui.Row

/**
 * The launcher's settings hub ("Home Screen Settings"), reached from Home
 * (long-press the center key), the app list's Options menu and the Settings
 * chooser. A short flat list, TurboText-style: each row opens its own
 * category screen, with system hand-offs collected under Advanced.
 */
class SettingsActivity : BaseListActivity() {

    private lateinit var adapter: ListRowAdapter
    private val actions = HashMap<String, () -> Unit>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        titleView.text = getString(R.string.title_settings)

        actions[ID_APPEARANCE] = { open(AppearanceSettingsActivity::class.java) }
        actions[ID_HOME_KEYS] = { open(HomeKeysSettingsActivity::class.java) }
        actions[ID_APPS_DRAWER] = { open(AppsSettingsActivity::class.java) }
        actions[ID_NOTIFICATIONS] = { open(NotificationSettingsActivity::class.java) }
        actions[ID_ADVANCED] = { open(AdvancedSettingsActivity::class.java) }

        adapter = ListRowAdapter(onClick = { dispatch(it) })
        listView.adapter = adapter
        adapter.submit(buildRows())

        softKeys.setLabels(null, getString(R.string.softkey_select), null)
        softKeys.setOnCenterClick { focusedPosition().takeIf { it >= 0 }?.let { dispatch(it) } }
        focusFirst()
    }

    private fun buildRows(): List<Row> = listOf(
        Row(
            id = ID_APPEARANCE,
            title = getString(R.string.cat_appearance),
            subtitle = getString(R.string.cat_appearance_sub),
            chevron = true,
        ),
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
        Row(
            id = ID_NOTIFICATIONS,
            title = getString(R.string.cat_notifications),
            subtitle = getString(R.string.cat_notifications_sub),
            chevron = true,
        ),
        Row(
            id = ID_ADVANCED,
            title = getString(R.string.cat_advanced),
            subtitle = getString(R.string.cat_advanced_sub),
            chevron = true,
        ),
    )

    private fun dispatch(position: Int) {
        adapter.rowAt(position)?.id?.let { actions[it]?.invoke() }
    }

    private fun open(cls: Class<*>) = startActivity(Intent(this, cls))

    companion object {
        private const val ID_APPEARANCE = "appearance"
        private const val ID_HOME_KEYS = "home_keys"
        private const val ID_APPS_DRAWER = "apps_drawer"
        private const val ID_NOTIFICATIONS = "notifications"
        private const val ID_ADVANCED = "advanced"
    }
}
