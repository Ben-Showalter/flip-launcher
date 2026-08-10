package com.flipos.launcher.activities

import com.flipos.launcher.R

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import com.flipos.launcher.data.AppRepository
import com.flipos.launcher.data.LauncherPrefs
import com.flipos.launcher.ui.ListRowAdapter
import com.flipos.launcher.ui.Row

/** Home screen key bindings and shortcut management. */
class HomeKeysSettingsActivity : BaseListActivity() {

    private lateinit var prefs: LauncherPrefs
    private lateinit var adapter: ListRowAdapter
    private val actions = HashMap<String, () -> Unit>()

    private val pickRightKeyApp = registerForActivityResult(StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.getStringExtra(AppPickerActivity.EXTRA_APP_KEY)?.let { key ->
                prefs.setRightKeyApp(key)
                AppRepository.resolveComponent(this, key)?.label?.let {
                    Toast.makeText(this, getString(R.string.settings_right_key_set, it), Toast.LENGTH_SHORT).show()
                }
                refreshRows()
            }
        }
    }

    private val pickLeftKeyApp = registerForActivityResult(StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.getStringExtra(AppPickerActivity.EXTRA_APP_KEY)?.let { key ->
                prefs.setLeftKeyApp(key)
                AppRepository.resolveComponent(this, key)?.label?.let {
                    Toast.makeText(this, getString(R.string.settings_left_key_set, it), Toast.LENGTH_SHORT).show()
                }
                refreshRows()
            }
        }
    }

    private val pickBackLongPressApp = registerForActivityResult(StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.getStringExtra(AppPickerActivity.EXTRA_APP_KEY)?.let { key ->
                prefs.setBackLongPressApp(key)
                AppRepository.resolveComponent(this, key)?.label?.let {
                    Toast.makeText(this, getString(R.string.back_longpress_app_set, it), Toast.LENGTH_SHORT).show()
                }
                refreshRows()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = LauncherPrefs(this)
        titleView.text = getString(R.string.cat_home_keys)

        actions[ID_LEFT_KEY] = { chooseLeftKey() }
        actions[ID_RIGHT_KEY] = { chooseRightKey() }
        actions[ID_BACK_LONGPRESS] = { configureBackLongPress() }
        actions[ID_SHORTCUTS] = { startActivity(Intent(this, ShortcutConfigActivity::class.java)) }

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
        val leftLabel = prefs.getLeftKeyApp()?.let { AppRepository.resolveComponent(this, it)?.label }
            ?: getString(R.string.settings_left_key_notices)
        val rightLabel = prefs.getRightKeyApp()?.let { AppRepository.resolveComponent(this, it)?.label }
            ?: getString(R.string.settings_right_key_contacts)
        val backLabel = prefs.getBackLongPressApp()?.let { AppRepository.resolveComponent(this, it)?.label }
            ?: getString(R.string.back_longpress_not_set)

        adapter.submit(
            listOf(
                Row.section(getString(R.string.sec_soft_keys)),
                Row(id = ID_LEFT_KEY, title = getString(R.string.settings_left_key), trailing = leftLabel, chevron = true),
                Row(id = ID_RIGHT_KEY, title = getString(R.string.settings_right_key), trailing = rightLabel, chevron = true),
                Row.section(getString(R.string.sec_buttons)),
                Row(id = ID_BACK_LONGPRESS, title = getString(R.string.opt_back_longpress), trailing = backLabel, chevron = true),
                Row.section(getString(R.string.sec_shortcuts)),
                Row(id = ID_SHORTCUTS, title = getString(R.string.opt_customize_shortcuts), chevron = true),
            ),
        )
    }

    private fun dispatch(position: Int) {
        adapter.rowAt(position)?.id?.let { actions[it]?.invoke() }
    }

    private fun chooseRightKey() {
        val items = mutableListOf(getString(R.string.back_longpress_choose))
        if (prefs.getRightKeyApp() != null) items.add(getString(R.string.settings_right_key_contacts))
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_right_key)
            .setItems(items.toTypedArray()) { _, which ->
                when (which) {
                    0 -> pickRightKeyApp.launch(Intent(this, AppPickerActivity::class.java))
                    1 -> {
                        prefs.setRightKeyApp(null)
                        Toast.makeText(this, R.string.settings_right_key_cleared, Toast.LENGTH_SHORT).show()
                        refreshRows()
                    }
                }
            }
            .show()
    }

    private fun chooseLeftKey() {
        val items = mutableListOf(getString(R.string.back_longpress_choose))
        if (prefs.getLeftKeyApp() != null) items.add(getString(R.string.settings_left_key_notices))
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_left_key)
            .setItems(items.toTypedArray()) { _, which ->
                when (which) {
                    0 -> pickLeftKeyApp.launch(Intent(this, AppPickerActivity::class.java))
                    1 -> {
                        prefs.setLeftKeyApp(null)
                        Toast.makeText(this, R.string.settings_left_key_cleared, Toast.LENGTH_SHORT).show()
                        refreshRows()
                    }
                }
            }
            .show()
    }

    private fun configureBackLongPress() {
        if (prefs.getBackLongPressApp() == null) {
            pickBackLongPressApp.launch(Intent(this, AppPickerActivity::class.java))
            return
        }
        AlertDialog.Builder(this)
            .setItems(
                arrayOf(getString(R.string.back_longpress_choose), getString(R.string.back_longpress_clear)),
            ) { _, which ->
                when (which) {
                    0 -> pickBackLongPressApp.launch(Intent(this, AppPickerActivity::class.java))
                    1 -> {
                        prefs.setBackLongPressApp(null)
                        refreshRows()
                        Toast.makeText(this, R.string.back_longpress_app_cleared, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .show()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_SOFT_LEFT) {
            finish()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    companion object {
        private const val ID_LEFT_KEY = "left_key"
        private const val ID_RIGHT_KEY = "right_key"
        private const val ID_BACK_LONGPRESS = "back_longpress"
        private const val ID_SHORTCUTS = "shortcuts"
    }
}
