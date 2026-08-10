package com.flipos.launcher.activities

import com.flipos.launcher.R

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import com.flipos.launcher.data.LauncherPrefs
import com.flipos.launcher.ui.ListRowAdapter
import com.flipos.launcher.ui.Row

/** App drawer layout and hidden-app management. */
class AppsSettingsActivity : BaseListActivity() {

    private lateinit var prefs: LauncherPrefs
    private lateinit var adapter: ListRowAdapter
    private val actions = HashMap<String, () -> Unit>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = LauncherPrefs(this)
        titleView.text = getString(R.string.cat_apps_drawer)

        actions[ID_DRAWER_VIEW] = {
            prefs.setDrawerListViewEnabled(!prefs.isDrawerListViewEnabled())
            refreshRows()
        }
        actions[ID_HIDE_APPS] = { startActivity(Intent(this, HideAppsActivity::class.java)) }

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
        refreshRows()
    }

    private fun refreshRows() {
        val viewLabel = getString(
            if (prefs.isDrawerListViewEnabled()) R.string.settings_drawer_view_list else R.string.settings_drawer_view_grid,
        )
        adapter.submit(
            listOf(
                Row.section(getString(R.string.sec_app_drawer)),
                Row(
                    id = ID_DRAWER_VIEW,
                    title = getString(R.string.settings_drawer_view),
                    subtitle = getString(R.string.settings_drawer_view_sub),
                    trailing = viewLabel,
                ),
                Row(id = ID_HIDE_APPS, title = getString(R.string.opt_hide_apps), chevron = true),
            ),
        )
    }

    private fun dispatch(position: Int) {
        adapter.rowAt(position)?.id?.let { actions[it]?.invoke() }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_SOFT_LEFT) {
            finish()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    companion object {
        private const val ID_DRAWER_VIEW = "drawer_view"
        private const val ID_HIDE_APPS = "hide_apps"
    }
}
