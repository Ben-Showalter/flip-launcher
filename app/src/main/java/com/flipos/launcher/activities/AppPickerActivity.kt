package com.flipos.launcher.activities

import com.flipos.launcher.R

import android.content.Intent
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.appcompat.app.AlertDialog
import com.flipos.launcher.data.AppInfo
import com.flipos.launcher.data.AppRepository
import com.flipos.launcher.ui.ListRowAdapter
import com.flipos.launcher.ui.Row
import com.flipos.launcher.util.BackgroundLoader

/**
 * Pick-an-app dialog used when assigning a Home shortcut. Returns the chosen
 * app's [key] via [EXTRA_APP_KEY].
 *
 * Select an app to pin its main entry, or pick "Choose activity" from Options
 * to drill into that app and pin a specific activity instead. Lists all installed
 * apps (including hidden ones, so a hidden app can still be pinned to Home).
 */
class AppPickerActivity : BaseListActivity() {

    private lateinit var adapter: ListRowAdapter
    private var apps: List<AppInfo> = emptyList()
    private val loader = BackgroundLoader()

    private val activityPicker = registerForActivityResult(StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.getStringExtra(EXTRA_APP_KEY)?.let { key ->
                // Forward the chosen activity back to whoever opened the app picker.
                setResult(RESULT_OK, Intent().putExtra(EXTRA_APP_KEY, key))
                finish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        titleView.text = getString(R.string.title_pick_app)

        adapter = ListRowAdapter(onClick = { pick(it) })
        listView.adapter = adapter

        softKeys.setLabels(
            null,
            getString(R.string.softkey_select),
            getString(R.string.softkey_options),
        )
        softKeys.setOnCenterClick { pick(focusedPosition()) }

        loadApps()
    }

    override fun onDestroy() {
        loader.cancel()
        super.onDestroy()
    }

    private fun loadApps() {
        loader.load(
            produce = { AppRepository.getAllApps(this) },
            consume = { loaded ->
                if (isDestroyed) return@load
                apps = loaded
                adapter.submit(loaded.map { Row(title = it.label, icon = it.icon) })
                focusFirst()
            },
        )
    }

    private fun pick(position: Int) {
        val app = apps.getOrNull(position) ?: return
        setResult(RESULT_OK, Intent().putExtra(EXTRA_APP_KEY, app.key))
        finish()
    }

    private fun openActivities(position: Int) {
        val app = apps.getOrNull(position) ?: return
        activityPicker.launch(
            Intent(this, ActivityPickerActivity::class.java)
                .putExtra(ActivityPickerActivity.EXTRA_PACKAGE, app.packageName)
                .putExtra(ActivityPickerActivity.EXTRA_TITLE, app.label),
        )
    }

    override fun onOptionsKey() {
        val position = focusedPosition()
        if (apps.getOrNull(position) == null) return
        AlertDialog.Builder(this)
            .setItems(arrayOf(getString(R.string.menu_choose_activity))) { _, which ->
                if (which == 0) openActivities(position)
            }
            .show()
    }

    companion object {
        const val EXTRA_APP_KEY = "app_key"
    }
}
