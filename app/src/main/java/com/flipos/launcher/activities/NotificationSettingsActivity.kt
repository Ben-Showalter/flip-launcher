package com.flipos.launcher.activities

import com.flipos.launcher.R

import android.content.Intent
import android.os.Bundle
import android.speech.tts.Voice
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.flipos.launcher.data.LauncherPrefs
import com.flipos.launcher.ui.ListRowAdapter
import com.flipos.launcher.ui.Row
import com.flipos.launcher.util.ReadAloudSpeaker

/**
 * Notification settings. Opens to a short menu - Home Banner, Read Aloud
 * (each its own screen, via [EXTRA_GROUP]) and the Icon Dots toggle; the
 * access grants live in [AdvancedSettingsActivity].
 */
class NotificationSettingsActivity : BaseListActivity() {

    private lateinit var prefs: LauncherPrefs
    private lateinit var adapter: ListRowAdapter
    private val actions = HashMap<String, () -> Unit>()

    /** Which group this screen shows ([GROUP_BANNER] / [GROUP_READ_ALOUD]), or null for the menu. */
    private var group: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = LauncherPrefs(this)
        group = intent.getStringExtra(EXTRA_GROUP)
        titleView.text = getString(
            when (group) {
                GROUP_BANNER -> R.string.notif_group_banner
                GROUP_READ_ALOUD -> R.string.sec_read_aloud
                else -> R.string.cat_notifications
            },
        )

        actions[ID_GROUP_BANNER] = { openGroup(GROUP_BANNER) }
        actions[ID_GROUP_READ_ALOUD] = { openGroup(GROUP_READ_ALOUD) }
        actions[ID_CALLS] = {
            prefs.setCallBadgeEnabled(!prefs.isCallBadgeEnabled()); refreshRows()
        }
        actions[ID_MESSAGES] = {
            prefs.setMessageBadgeEnabled(!prefs.isMessageBadgeEnabled()); refreshRows()
        }
        actions[ID_OTHER] = {
            prefs.setOtherBadgeEnabled(!prefs.isOtherBadgeEnabled()); refreshRows()
        }
        actions[ID_HIDE_TEXT] = {
            prefs.setNotificationTextHidden(!prefs.isNotificationTextHidden()); refreshRows()
        }
        actions[ID_READ_ALOUD] = { chooseReadAloudMode() }
        actions[ID_READ_ALOUD_VOICE] = { chooseReadAloudVoice() }
        actions[ID_READ_ALOUD_RATE] = { chooseReadAloudRate() }
        actions[ID_DOTS] = {
            prefs.setIconNotificationDotEnabled(!prefs.isIconNotificationDotEnabled()); refreshRows()
        }

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

    private fun openGroup(id: String) =
        startActivity(Intent(this, NotificationSettingsActivity::class.java).putExtra(EXTRA_GROUP, id))

    private fun refreshRows() {
        adapter.submit(
            when (group) {
                GROUP_BANNER -> listOf(
                    Row(id = ID_CALLS, title = getString(R.string.settings_notif_calls), toggle = prefs.isCallBadgeEnabled()),
                    Row(id = ID_MESSAGES, title = getString(R.string.settings_notif_messages), toggle = prefs.isMessageBadgeEnabled()),
                    Row(id = ID_OTHER, title = getString(R.string.settings_notif_other), toggle = prefs.isOtherBadgeEnabled()),
                    Row(
                        id = ID_HIDE_TEXT,
                        title = getString(R.string.settings_notif_hide_text),
                        subtitle = getString(R.string.settings_notif_hide_text_sub),
                        toggle = prefs.isNotificationTextHidden(),
                    ),
                )
                GROUP_READ_ALOUD -> listOf(
                    Row(
                        id = ID_READ_ALOUD,
                        title = getString(R.string.settings_read_aloud),
                        subtitle = getString(R.string.settings_read_aloud_sub),
                        trailing = getString(readAloudModeLabel(prefs.getReadAloudMode())),
                        chevron = true,
                    ),
                    Row(
                        id = ID_READ_ALOUD_VOICE,
                        title = getString(R.string.settings_read_aloud_voice),
                        trailing = prefs.getReadAloudVoice() ?: getString(R.string.settings_read_aloud_voice_default),
                        chevron = true,
                    ),
                    Row(
                        id = ID_READ_ALOUD_RATE,
                        title = getString(R.string.settings_read_aloud_rate),
                        trailing = getString(readAloudRateLabel(prefs.getReadAloudRate())),
                        chevron = true,
                    ),
                )
                else -> listOf(
                    Row(
                        id = ID_GROUP_BANNER,
                        title = getString(R.string.notif_group_banner),
                        subtitle = getString(R.string.notif_group_banner_sub),
                        chevron = true,
                    ),
                    Row(
                        id = ID_GROUP_READ_ALOUD,
                        title = getString(R.string.sec_read_aloud),
                        subtitle = getString(R.string.settings_read_aloud_sub),
                        chevron = true,
                    ),
                    Row(id = ID_DOTS, title = getString(R.string.settings_notif_icon_dots), toggle = prefs.isIconNotificationDotEnabled()),
                )
            },
        )
    }

    private fun dispatch(position: Int) {
        adapter.rowAt(position)?.id?.let { actions[it]?.invoke() }
    }

    private fun readAloudModeLabel(mode: String): Int =
        READ_ALOUD_MODE_LABELS[READ_ALOUD_MODES.indexOf(mode).coerceAtLeast(0)]

    /** Labels the saved rate, treating anything off the three presets as Normal. */
    private fun readAloudRateLabel(rate: Float): Int =
        READ_ALOUD_RATE_LABELS[READ_ALOUD_RATES.indexOf(rate).takeIf { it >= 0 } ?: 1]

    private fun chooseReadAloudMode() {
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_read_aloud)
            .setItems(READ_ALOUD_MODE_LABELS.map { getString(it) }.toTypedArray()) { _, which ->
                prefs.setReadAloudMode(READ_ALOUD_MODES[which])
                refreshRows()
            }
            .show()
    }

    private fun chooseReadAloudVoice() {
        ReadAloudSpeaker.listVoices(this) { voices ->
            if (isFinishing) return@listVoices
            if (voices.isEmpty()) {
                Toast.makeText(this, R.string.settings_read_aloud_no_voices, Toast.LENGTH_LONG).show()
                return@listVoices
            }
            AlertDialog.Builder(this)
                .setTitle(R.string.settings_read_aloud_voice)
                .setItems(voices.map { voiceLabel(it) }.toTypedArray()) { _, which ->
                    ReadAloudSpeaker.setVoice(this, voices[which].name)
                    ReadAloudSpeaker.speak(this, getString(R.string.settings_read_aloud_voice_preview))
                    refreshRows()
                }
                .show()
        }
    }

    private fun voiceLabel(voice: Voice): String {
        val quality = when {
            voice.quality >= Voice.QUALITY_VERY_HIGH -> R.string.voice_quality_very_high
            voice.quality >= Voice.QUALITY_HIGH -> R.string.voice_quality_high
            voice.quality >= Voice.QUALITY_NORMAL -> R.string.voice_quality_normal
            else -> R.string.voice_quality_low
        }
        return "${voice.name} (${getString(quality)})"
    }

    private fun chooseReadAloudRate() {
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_read_aloud_rate)
            .setItems(READ_ALOUD_RATE_LABELS.map { getString(it) }.toTypedArray()) { _, which ->
                ReadAloudSpeaker.setSpeechRate(this, READ_ALOUD_RATES[which])
                ReadAloudSpeaker.speak(this, getString(R.string.settings_read_aloud_rate_preview))
                refreshRows()
            }
            .show()
    }

    companion object {
        /** Opens just one group of settings: [GROUP_BANNER] or [GROUP_READ_ALOUD]. */
        const val EXTRA_GROUP = "group"
        private const val GROUP_BANNER = "banner"
        private const val GROUP_READ_ALOUD = "read_aloud"
        private const val ID_GROUP_BANNER = "group_banner"
        private const val ID_GROUP_READ_ALOUD = "group_read_aloud"
        private const val ID_CALLS = "calls"
        private const val ID_MESSAGES = "messages"
        private const val ID_OTHER = "other"
        private const val ID_HIDE_TEXT = "hide_text"
        private const val ID_DOTS = "dots"
        private const val ID_READ_ALOUD = "read_aloud"
        private const val ID_READ_ALOUD_VOICE = "read_aloud_voice"
        private const val ID_READ_ALOUD_RATE = "read_aloud_rate"

        private val READ_ALOUD_MODES = listOf(
            LauncherPrefs.READ_ALOUD_NEVER,
            LauncherPrefs.READ_ALOUD_ALWAYS,
            LauncherPrefs.READ_ALOUD_BLUETOOTH,
        )
        private val READ_ALOUD_MODE_LABELS = listOf(
            R.string.read_aloud_never,
            R.string.read_aloud_always,
            R.string.read_aloud_bluetooth,
        )
        private val READ_ALOUD_RATES = listOf(0.75f, 1.0f, 1.25f)
        private val READ_ALOUD_RATE_LABELS = listOf(
            R.string.read_aloud_rate_slower,
            R.string.read_aloud_rate_normal,
            R.string.read_aloud_rate_faster,
        )
    }
}
