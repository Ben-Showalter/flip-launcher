package com.flipos.launcher.activities

import com.flipos.launcher.R

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import com.flipos.launcher.data.AppInfo
import com.flipos.launcher.data.AppRepository
import com.flipos.launcher.ui.ListRowAdapter
import com.flipos.launcher.ui.Row
import com.flipos.launcher.util.BackgroundLoader

/**
 * Lists the launchable, exported activities of a single app so the user can pin
 * a deep screen (not just the app's main entry) to a Home shortcut. Returns the
 * chosen activity component via [AppPickerActivity.EXTRA_APP_KEY].
 */
class ActivityPickerActivity : BaseListActivity() {

    private lateinit var adapter: ListRowAdapter
    private var activities: List<AppInfo> = emptyList()
    private lateinit var targetPackage: String
    private val loader = BackgroundLoader()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        targetPackage = intent.getStringExtra(EXTRA_PACKAGE).orEmpty()
        titleView.text = intent.getStringExtra(EXTRA_TITLE) ?: getString(R.string.title_pick_activity)

        adapter = ListRowAdapter(onClick = { pick(it) })
        listView.adapter = adapter

        softKeys.setLabels(
            getString(R.string.softkey_back),
            getString(R.string.softkey_select),
            null,
        )
        softKeys.setOnLeftClick { finish() }
        softKeys.setOnCenterClick { pick(focusedPosition()) }

        loadActivities()
    }

    override fun onDestroy() {
        loader.cancel()
        super.onDestroy()
    }

    private fun loadActivities() {
        loader.load(
            produce = { AppRepository.getActivities(this, targetPackage) },
            consume = { loaded ->
                if (isDestroyed) return@load
                activities = loaded
                adapter.submit(
                    loaded.map { Row(title = it.label, trailing = shortClassName(it.activityName), icon = it.icon) },
                )
                focusFirst()
            },
        )
    }

    private fun pick(position: Int) {
        val activity = activities.getOrNull(position) ?: return
        setResult(RESULT_OK, Intent().putExtra(AppPickerActivity.EXTRA_APP_KEY, activity.key))
        finish()
    }

    /** "com.app.ui.SettingsActivity" -> "SettingsActivity" to disambiguate rows. */
    private fun shortClassName(name: String) = name.substringAfterLast('.')

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_SOFT_LEFT) {
            finish()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    companion object {
        const val EXTRA_PACKAGE = "package"
        const val EXTRA_TITLE = "title"
    }
}
