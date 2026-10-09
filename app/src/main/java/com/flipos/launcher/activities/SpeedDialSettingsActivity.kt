package com.flipos.launcher.activities

import com.flipos.launcher.R

import android.os.Bundle
import android.view.KeyEvent
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.flipos.launcher.data.LauncherPrefs
import com.flipos.launcher.ui.ListRowAdapter
import com.flipos.launcher.ui.Row
import com.flipos.launcher.util.NumberAssigner
import com.flipos.launcher.util.openSystemSpeedDial

/**
 * The launcher's own speed-dial slots (0, 2-9; long-pressed from Home to
 * call immediately, see [MainActivity]). Used when the phone's dialer data
 * can't be read - the E4610, see util/SystemSpeedDial.kt - and as a fallback
 * for any digit the phone's own speed dial leaves empty. Key 1 is a locked
 * row, always voicemail.
 *
 * Center/OK (or the digit itself) assigns the focused slot; Options clears
 * it or opens the phone's own Speed Dial screen.
 */
class SpeedDialSettingsActivity : BaseListActivity() {

    private lateinit var prefs: LauncherPrefs
    private lateinit var adapter: ListRowAdapter

    /** Display order: keypad reading order, 1 (locked) through 9, then 0. */
    private val digitsInOrder = listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 0)

    private var pendingDigit = -1

    /** Digit keys whose DOWN landed here, so the row opens on UP. */
    private val pressedDigits = mutableSetOf<Int>()

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

        softKeys.setLabels(null, getString(R.string.softkey_select), getString(R.string.softkey_options))
        softKeys.setOnCenterClick { onRowClick(focusedPosition()) }
        refreshRows()

        // Arrived from Home (long-pressing an unassigned digit) - jump
        // straight into assigning that row instead of just focusing it.
        val focusDigit = intent.getIntExtra(EXTRA_FOCUS_DIGIT, -1)
        if (focusDigit >= 0 && savedInstanceState == null) {
            onRowClick(digitsInOrder.indexOf(focusDigit))
        }
        focusFirst()
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
        pendingDigit = digit
        numberAssigner.start()
    }

    override fun onOptionsKey() {
        val digit = digitsInOrder.getOrNull(focusedPosition())
        val canClear = digit != null && digit != LOCKED_VOICEMAIL_DIGIT && prefs.getSpeedDial(digit) != null
        val labels = listOfNotNull(
            if (canClear) getString(R.string.softkey_clear) else null,
            getString(R.string.menu_phone_speed_dial),
        )
        AlertDialog.Builder(this)
            .setItems(labels.toTypedArray()) { _, which ->
                if (canClear && which == 0) {
                    prefs.clearSpeedDial(digit!!)
                    refreshRows()
                } else {
                    openSystemSpeedDial()
                }
            }
            .show()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) {
            if (event.repeatCount == 0) pressedDigits.add(keyCode)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) {
            if (pressedDigits.remove(keyCode) && !event.isCanceled) {
                val index = digitsInOrder.indexOf(keyCode - KeyEvent.KEYCODE_0)
                if (index >= 0) onRowClick(index)
            }
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    companion object {
        private const val LOCKED_VOICEMAIL_DIGIT = 1

        /** Digit to jump straight into assigning on launch (see Home's unassigned-digit long-press). */
        const val EXTRA_FOCUS_DIGIT = "focus_digit"
    }
}
