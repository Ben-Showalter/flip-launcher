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
import com.flipos.launcher.util.openSystemSettings

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

    private val pickMenuKeyApp = registerForActivityResult(StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.getStringExtra(AppPickerActivity.EXTRA_APP_KEY)?.let { key ->
                prefs.setMenuKeyApp(key)
                AppRepository.resolveComponent(this, key)?.label?.let {
                    Toast.makeText(this, getString(R.string.key_assigned_toast, it), Toast.LENGTH_SHORT).show()
                }
                refreshRows()
            }
        }
    }

    /** Which D-pad/Camera key is awaiting an app from [pickDirectionalKeyApp] (0 = none). */
    private var pendingDirectionalKey = 0

    private val pickDirectionalKeyApp = registerForActivityResult(StartActivityForResult()) { result ->
        val target = pendingDirectionalKey
        pendingDirectionalKey = 0
        if (result.resultCode == RESULT_OK && target != 0) {
            result.data?.getStringExtra(AppPickerActivity.EXTRA_APP_KEY)?.let { key ->
                setDirectionalKeyApp(target, key)
                AppRepository.resolveComponent(this, key)?.label?.let {
                    Toast.makeText(this, getString(R.string.key_assigned_toast, it), Toast.LENGTH_SHORT).show()
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
        actions[ID_MENU_KEY] = { configureMenuKey() }
        actions[ID_DPAD_UP] = { configureDirectionalKey(KeyEvent.KEYCODE_DPAD_UP) }
        actions[ID_DPAD_DOWN] = { configureDirectionalKey(KeyEvent.KEYCODE_DPAD_DOWN) }
        actions[ID_DPAD_LEFT] = { configureDirectionalKey(KeyEvent.KEYCODE_DPAD_LEFT) }
        actions[ID_DPAD_RIGHT] = { configureDirectionalKey(KeyEvent.KEYCODE_DPAD_RIGHT) }
        actions[ID_CAMERA_KEY] = { configureDirectionalKey(KeyEvent.KEYCODE_CAMERA) }
        actions[ID_ASSISTANT_KEY] = { configureDirectionalKey(KeyEvent.KEYCODE_F4) }
        // Outer buttons: an assigned app opens from Home; unassigned, the
        // phone's own key setting applies (see MainActivity.dispatchKeyEvent).
        EXTRA_ROW_IDS.forEachIndexed { index, id ->
            actions[id] = { configureExtraKey(LauncherPrefs.EXTRA_KEYCODES[index]) }
        }
        actions[ID_SPEED_DIAL] = { startActivity(Intent(this, SpeedDialSettingsActivity::class.java)) }

        adapter = ListRowAdapter(onClick = { dispatch(it) })
        listView.adapter = adapter

        softKeys.setLabels(
            null,
            getString(R.string.softkey_select),
            null,
        )
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
            ?: getString(R.string.settings_left_key_contacts)
        val rightLabel = prefs.getRightKeyApp()?.let { AppRepository.resolveComponent(this, it)?.label }
            ?: getString(R.string.settings_right_key_sms)
        val backLabel = prefs.getBackLongPressApp()?.let { AppRepository.resolveComponent(this, it)?.label }
            ?: getString(R.string.back_longpress_not_set)
        val menuLabel = prefs.getMenuKeyApp()?.let { AppRepository.resolveComponent(this, it)?.label }
            ?: getString(R.string.back_longpress_not_set)
        fun directionalLabel(keyCode: Int) = getDirectionalKeyApp(keyCode)?.let { AppRepository.resolveComponent(this, it)?.label }
            ?: getString(R.string.back_longpress_not_set)
        fun extraLabel(keyCode: Int) = prefs.getExtraKeyApp(keyCode)?.let { AppRepository.resolveComponent(this, it)?.label }
            ?: getString(R.string.opt_extra_key_system)
        val cameraLabel = prefs.getCameraKeyApp()?.let { AppRepository.resolveComponent(this, it)?.label }
            ?: getString(R.string.opt_camera_key_default)
        val assistantLabel = prefs.getAssistantKeyApp()?.let { AppRepository.resolveComponent(this, it)?.label }
            ?: getString(R.string.opt_assistant_key_default)

        adapter.submit(
            listOf(
                Row.section(getString(R.string.sec_soft_keys)),
                Row(id = ID_LEFT_KEY, title = getString(R.string.settings_left_key), trailing = leftLabel, chevron = true),
                Row(id = ID_RIGHT_KEY, title = getString(R.string.settings_right_key), trailing = rightLabel, chevron = true),
                Row.section(getString(R.string.sec_buttons)),
                Row(id = ID_SPEED_DIAL, title = getString(R.string.opt_speed_dial), chevron = true),
                Row(id = ID_BACK_LONGPRESS, title = getString(R.string.opt_back_longpress), trailing = backLabel, chevron = true),
                Row(id = ID_MENU_KEY, title = getString(R.string.opt_menu_key), trailing = menuLabel, chevron = true),
                Row.section(getString(R.string.sec_dpad_keys)),
                Row(id = ID_DPAD_UP, title = getString(R.string.opt_dpad_up), trailing = directionalLabel(KeyEvent.KEYCODE_DPAD_UP), chevron = true),
                Row(id = ID_DPAD_DOWN, title = getString(R.string.opt_dpad_down), trailing = directionalLabel(KeyEvent.KEYCODE_DPAD_DOWN), chevron = true),
                Row(id = ID_DPAD_LEFT, title = getString(R.string.opt_dpad_left), trailing = directionalLabel(KeyEvent.KEYCODE_DPAD_LEFT), chevron = true),
                Row(id = ID_DPAD_RIGHT, title = getString(R.string.opt_dpad_right), trailing = directionalLabel(KeyEvent.KEYCODE_DPAD_RIGHT), chevron = true),
                Row(id = ID_CAMERA_KEY, title = getString(R.string.opt_camera_key), trailing = cameraLabel, chevron = true),
                Row(id = ID_ASSISTANT_KEY, title = getString(R.string.opt_assistant_key), trailing = assistantLabel, chevron = true),
                Row.section(getString(R.string.sec_extra_keys)),
                Row(id = ID_EXTRA_1, title = getString(R.string.opt_extra_key_1), trailing = extraLabel(LauncherPrefs.KEYCODE_EXTRA_1), chevron = true),
                Row(id = ID_EXTRA_2, title = getString(R.string.opt_extra_key_2), trailing = extraLabel(LauncherPrefs.KEYCODE_EXTRA_2), chevron = true),
                Row(id = ID_EXTRA_3, title = getString(R.string.opt_extra_key_3), trailing = extraLabel(LauncherPrefs.KEYCODE_EXTRA_3), chevron = true),
                Row(id = ID_EXTRA_4, title = getString(R.string.opt_extra_key_4), trailing = extraLabel(LauncherPrefs.KEYCODE_EXTRA_4), chevron = true),
            ),
        )
    }

    private fun dispatch(position: Int) {
        adapter.rowAt(position)?.id?.let { actions[it]?.invoke() }
    }

    private fun chooseRightKey() {
        val items = mutableListOf(getString(R.string.back_longpress_choose))
        if (prefs.getRightKeyApp() != null) items.add(getString(R.string.settings_right_key_sms))
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
        if (prefs.getLeftKeyApp() != null) items.add(getString(R.string.settings_left_key_contacts))
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

    private fun configureMenuKey() {
        if (prefs.getMenuKeyApp() == null) {
            pickMenuKeyApp.launch(Intent(this, AppPickerActivity::class.java))
            return
        }
        AlertDialog.Builder(this)
            .setItems(
                arrayOf(getString(R.string.back_longpress_choose), getString(R.string.back_longpress_clear)),
            ) { _, which ->
                when (which) {
                    0 -> pickMenuKeyApp.launch(Intent(this, AppPickerActivity::class.java))
                    1 -> {
                        prefs.setMenuKeyApp(null)
                        refreshRows()
                    }
                }
            }
            .show()
    }

    private fun getDirectionalKeyApp(keyCode: Int): String? = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP -> prefs.getDpadUpApp()
        KeyEvent.KEYCODE_DPAD_DOWN -> prefs.getDpadDownApp()
        KeyEvent.KEYCODE_DPAD_LEFT -> prefs.getDpadLeftApp()
        KeyEvent.KEYCODE_DPAD_RIGHT -> prefs.getDpadRightApp()
        KeyEvent.KEYCODE_CAMERA -> prefs.getCameraKeyApp()
        KeyEvent.KEYCODE_F4 -> prefs.getAssistantKeyApp()
        in LauncherPrefs.EXTRA_KEYCODES -> prefs.getExtraKeyApp(keyCode)
        else -> null
    }

    private fun setDirectionalKeyApp(keyCode: Int, key: String?) {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> prefs.setDpadUpApp(key)
            KeyEvent.KEYCODE_DPAD_DOWN -> prefs.setDpadDownApp(key)
            KeyEvent.KEYCODE_DPAD_LEFT -> prefs.setDpadLeftApp(key)
            KeyEvent.KEYCODE_DPAD_RIGHT -> prefs.setDpadRightApp(key)
            KeyEvent.KEYCODE_CAMERA -> prefs.setCameraKeyApp(key)
            KeyEvent.KEYCODE_F4 -> prefs.setAssistantKeyApp(key)
            in LauncherPrefs.EXTRA_KEYCODES -> prefs.setExtraKeyApp(keyCode, key)
        }
    }

    private fun configureDirectionalKey(keyCode: Int) {
        if (getDirectionalKeyApp(keyCode) == null) {
            pendingDirectionalKey = keyCode
            pickDirectionalKeyApp.launch(Intent(this, AppPickerActivity::class.java))
            return
        }
        AlertDialog.Builder(this)
            .setItems(
                arrayOf(getString(R.string.back_longpress_choose), getString(R.string.back_longpress_clear)),
            ) { _, which ->
                when (which) {
                    0 -> {
                        pendingDirectionalKey = keyCode
                        pickDirectionalKeyApp.launch(Intent(this, AppPickerActivity::class.java))
                    }
                    1 -> {
                        setDirectionalKeyApp(keyCode, null)
                        refreshRows()
                    }
                }
            }
            .show()
    }

    /** Choose / clear an app for an outer button, or hand it to the phone's own key setting. */
    private fun configureExtraKey(keyCode: Int) {
        val assigned = getDirectionalKeyApp(keyCode) != null
        val labels = listOfNotNull(
            getString(R.string.back_longpress_choose),
            if (assigned) getString(R.string.back_longpress_clear) else null,
            getString(R.string.extra_key_phone_setting),
        )
        AlertDialog.Builder(this)
            .setItems(labels.toTypedArray()) { _, which ->
                when (labels[which]) {
                    getString(R.string.back_longpress_choose) -> {
                        pendingDirectionalKey = keyCode
                        pickDirectionalKeyApp.launch(Intent(this, AppPickerActivity::class.java))
                    }
                    getString(R.string.back_longpress_clear) -> {
                        setDirectionalKeyApp(keyCode, null)
                        refreshRows()
                    }
                    else -> openSystemKeySettings()
                }
            }
            .show()
    }

    /**
     * The phone's own button-assignment screen: the E4610's PTT/key settings
     * (`com.android.settings/.afp.PttSettings`, confirmed via logcat) where
     * it exists, else the Settings app with a toast saying where to look.
     */
    private fun openSystemKeySettings() {
        try {
            startActivity(Intent(ACTION_KYOCERA_PTT_SETTINGS))
            return
        } catch (e: Exception) {
            // Not this model; fall back to the Settings app.
        }
        Toast.makeText(this, R.string.extra_key_system_toast, Toast.LENGTH_LONG).show()
        openSystemSettings()
    }

    companion object {
        private const val ID_LEFT_KEY = "left_key"
        private const val ID_RIGHT_KEY = "right_key"
        private const val ID_BACK_LONGPRESS = "back_longpress"
        private const val ID_MENU_KEY = "menu_key"
        private const val ID_DPAD_UP = "dpad_up"
        private const val ID_DPAD_DOWN = "dpad_down"
        private const val ID_DPAD_LEFT = "dpad_left"
        private const val ID_DPAD_RIGHT = "dpad_right"
        private const val ID_CAMERA_KEY = "camera_key"
        private const val ID_ASSISTANT_KEY = "assistant_key"
        private const val ID_EXTRA_1 = "extra_key_1"
        private const val ID_EXTRA_2 = "extra_key_2"
        private const val ID_EXTRA_3 = "extra_key_3"
        private const val ID_EXTRA_4 = "extra_key_4"
        private val EXTRA_ROW_IDS = listOf(ID_EXTRA_1, ID_EXTRA_2, ID_EXTRA_3, ID_EXTRA_4)
        private const val ID_SPEED_DIAL = "speed_dial"
        private const val ACTION_KYOCERA_PTT_SETTINGS = "kyocera.intent.action.PTT_SETTINGS"
    }
}
