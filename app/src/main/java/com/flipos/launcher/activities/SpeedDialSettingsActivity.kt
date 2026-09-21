package com.flipos.launcher.activities

import com.flipos.launcher.R

import android.app.AlertDialog
import android.os.Bundle
import android.view.KeyEvent
import android.widget.Toast
import com.flipos.launcher.data.LauncherPrefs
import com.flipos.launcher.ui.ListRowAdapter
import com.flipos.launcher.ui.Row
import com.flipos.launcher.util.NumberAssigner

/**
 * Assigns a phone number to each speed-dial-capable digit key (0, 2-9 - long-
 * pressed from Home to call it immediately, see [MainActivity]). Key 1 is a
 * locked row, always voicemail, never assignable.
 */
class SpeedDialSettingsActivity : BaseListActivity() {

    private lateinit var prefs: LauncherPrefs
    private lateinit var adapter: ListRowAdapter

    /** Display order: keypad reading order, 1 (locked) through 9, then 0. */
    private val digitsInOrder = listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 0)

    private var pendingDigit = -1

    private val numberAssigner = NumberAssigner(this) { number, label ->
        if (pendingDigit >= 0) {
            prefs.setSpeedDial(pendingDigit, number, label)
            pendingDigit = -1
            refreshRows()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = LauncherPrefs(this)
        titleView.text = getString(R.string.title_speed_dial)

        adapter = ListRowAdapter(onClick = { onRowClick(it) })
        listView.adapter = adapter

        softKeys.setLabels(
            getString(R.string.softkey_back),
            getString(R.string.softkey_select),
            getString(R.string.softkey_clear),
        )
        softKeys.setOnLeftClick { finish() }
        softKeys.setOnCenterClick { onRowClick(focusedPosition()) }
        softKeys.setOnRightClick { clearAt(focusedPosition()) }
        refreshRows()

        // Arrived as a shortcut from Home (long-pressing an unassigned digit) -
        // jump straight into assigning that row instead of just focusing it.
        val focusDigit = intent.getIntExtra(EXTRA_FOCUS_DIGIT, -1)
        if (focusDigit >= 0 && intent.getBooleanExtra(EXTRA_FOCUS_DIGIT_CONSUMED, false).not()) {
            intent.putExtra(EXTRA_FOCUS_DIGIT_CONSUMED, true)
            onRowClick(digitsInOrder.indexOf(focusDigit))
        } else {
            focusFirst()
        }
    }

    override fun onResume() {
        super.onResume()
        if (isRecreatingForAccent) return
        refreshRows()
    }

    private fun refreshRows() {
        adapter.submit(
            digitsInOrder.map { digit ->
                val slot = getString(R.string.shortcut_slot_label, digit)
                if (digit == LOCKED_VOICEMAIL_DIGIT) {
                    Row(
                        title = "$slot   ${getString(R.string.speed_dial_voicemail)}",
                        trailing = getString(R.string.speed_dial_locked),
                    )
                } else {
                    val entry = prefs.getSpeedDial(digit)
                    Row(title = "$slot   ${entry?.label ?: getString(R.string.speed_dial_not_set)}")
                }
            },
        )
    }

    /** Rows are keyed by position in [digitsInOrder]; the locked voicemail row explains itself instead of assigning. */
    private fun onRowClick(position: Int) {
        val digit = digitsInOrder.getOrNull(position) ?: return
        if (digit == LOCKED_VOICEMAIL_DIGIT) {
            Toast.makeText(this, R.string.speed_dial_voicemail_locked_toast, Toast.LENGTH_SHORT).show()
            return
        }
        if (prefs.getSpeedDial(digit) == null) {
            pendingDigit = digit
            numberAssigner.start()
            return
        }
        AlertDialog.Builder(this)
            .setItems(arrayOf(getString(R.string.speed_dial_change), getString(R.string.softkey_clear))) { _, which ->
                when (which) {
                    0 -> { pendingDigit = digit; numberAssigner.start() }
                    1 -> { prefs.clearSpeedDial(digit); refreshRows() }
                }
            }
            .show()
    }

    private fun clearAt(position: Int) {
        val digit = digitsInOrder.getOrNull(position) ?: return
        if (digit == LOCKED_VOICEMAIL_DIGIT) return
        prefs.clearSpeedDial(digit)
        refreshRows()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> {
                val index = digitsInOrder.indexOf(keyCode - KeyEvent.KEYCODE_0)
                if (index >= 0) onRowClick(index)
                return true
            }
            KeyEvent.KEYCODE_SOFT_LEFT -> { finish(); return true }
            KeyEvent.KEYCODE_SOFT_RIGHT -> { clearAt(focusedPosition()); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    companion object {
        private const val LOCKED_VOICEMAIL_DIGIT = 1

        /** Digit to jump straight into assigning on launch (see Home's unassigned-digit long-press shortcut). */
        const val EXTRA_FOCUS_DIGIT = "focus_digit"
        private const val EXTRA_FOCUS_DIGIT_CONSUMED = "focus_digit_consumed"
    }
}
