package com.flipos.launcher.util

import android.content.Intent
import android.provider.ContactsContract
import android.text.InputType
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.flipos.launcher.R

/**
 * Shared "assign a phone number" flow, used both by the in-place 5-second-hold
 * key assignment on Home ([com.flipos.launcher.activities.MainActivity]) and by
 * the Speed Dial settings screen: offers a choice between picking a contact and
 * typing a number by hand, then reports back the chosen number plus a display
 * label (the contact's name, or the number itself when typed manually).
 *
 * Must be constructed as a field initializer (before onCreate/onStart), like
 * any other `registerForActivityResult` registration.
 */
class NumberAssigner(
    private val activity: AppCompatActivity,
    private val onPicked: (number: String, label: String) -> Unit,
) {
    private val contactPicker = activity.registerForActivityResult(StartActivityForResult()) { result ->
        val uri = result.data?.data ?: return@registerForActivityResult
        activity.contentResolver.query(
            uri,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val number = cursor.getString(0)
                val name = cursor.getString(1)
                if (!number.isNullOrBlank()) {
                    onPicked(number, if (name.isNullOrBlank()) number else name)
                }
            }
        }
    }

    /** Shows the "Pick contact / Enter number manually" chooser. */
    fun start() {
        AlertDialog.Builder(activity)
            .setItems(
                arrayOf(
                    activity.getString(R.string.speed_dial_pick_contact),
                    activity.getString(R.string.speed_dial_enter_number),
                ),
            ) { _, which ->
                when (which) {
                    0 -> pickContact()
                    1 -> enterManually()
                }
            }
            .show()
    }

    private fun pickContact() {
        try {
            contactPicker.launch(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI))
        } catch (e: Exception) {
            Toast.makeText(activity, R.string.toast_no_contacts, Toast.LENGTH_SHORT).show()
        }
    }

    private fun enterManually() {
        val input = EditText(activity).apply { inputType = InputType.TYPE_CLASS_PHONE }
        AlertDialog.Builder(activity)
            .setTitle(R.string.speed_dial_enter_number)
            .setView(input)
            .setPositiveButton(R.string.speed_dial_save) { _, _ ->
                val number = input.text.toString().trim()
                if (number.isNotEmpty()) onPicked(number, number)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
