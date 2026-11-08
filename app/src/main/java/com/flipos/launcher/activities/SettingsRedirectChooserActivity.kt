package com.flipos.launcher.activities

import com.flipos.launcher.R

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/**
 * Asked over the phone's notification-sound picker (see
 * [com.flipos.launcher.util.SettingsRedirects]): open the launcher's
 * Notifications settings, or keep the phone's picker. Invisible apart from
 * its dialog; finishing it leaves the phone's screen showing.
 */
class SettingsRedirectChooserActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AlertDialog.Builder(this)
            .setTitle(R.string.redirect_notifications_title)
            .setItems(
                arrayOf(
                    getString(R.string.redirect_notifications_home),
                    getString(R.string.redirect_notifications_phone),
                ),
            ) { _, which ->
                if (which == 0) startActivity(Intent(this, NotificationSettingsActivity::class.java))
                finish()
            }
            .setOnCancelListener { finish() }
            .show()
    }
}
