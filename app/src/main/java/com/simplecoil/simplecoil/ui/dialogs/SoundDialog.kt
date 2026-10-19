/*
 * Copyright (C) 2026 Ethan Yonker
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.simplecoil.simplecoil.ui.dialogs

import android.content.Context
import android.view.LayoutInflater
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import com.simplecoil.simplecoil.AppContainer
import com.simplecoil.simplecoil.R
import com.simplecoil.simplecoil.databinding.DialogSoundBinding
import com.simplecoil.simplecoil.databinding.SoundSettingsSectionBinding
import com.simplecoil.simplecoil.domain.session.SessionSound
import com.simplecoil.simplecoil.hardware.audio.AudioSettings
import com.simplecoil.simplecoil.hardware.audio.SoundCues

/**
 * A player's sound settings (map design: Audio): how loud the effects and the radio callouts
 * are, callouts on, off or essential only, and vibration. Test plays a shot, a drop coming in
 * and a capture, with what is set. Shown in the player settings (on the server, for the player
 * being edited), or on its own ([SoundDialog]) when the server doesn't allow player settings.
 */
class SoundSettingsSection(
    private val d: SoundSettingsSectionBinding,
    /** What the section opens with. */
    val initial: AudioSettings,
    /** Test, with what is set; null hides it (on the server, the player's phone isn't this one). */
    onTest: ((AudioSettings) -> Unit)?,
) {

    init {
        d.soundEffectsSeek.progress = (initial.effectsVolume * 100).toInt()
        d.soundVoiceSeek.progress = (initial.voiceVolume * 100).toInt()
        d.soundVoiceSwitch.isChecked = initial.voiceOn
        d.soundEssentialSwitch.isChecked = initial.essentialOnly
        d.soundVibrationSwitch.isChecked = initial.vibration
        showVoice()
        d.soundVoiceSwitch.setOnCheckedChangeListener { _, _ -> showVoice() }
        d.soundTestButton.isVisible = onTest != null
        d.soundTestButton.setOnClickListener { onTest?.invoke(read()) }
    }

    private fun showVoice() {
        // No callouts at the moment: nothing to turn on or off
        d.soundVoiceSwitch.isVisible = SoundCues.hasCallouts
        val on = SoundCues.hasCallouts && d.soundVoiceSwitch.isChecked
        d.soundVoiceTitle.isVisible = on
        d.soundVoiceSeek.isVisible = on
        d.soundEssentialSwitch.isVisible = on
    }

    fun read() = AudioSettings(
        effectsVolume = d.soundEffectsSeek.progress / 100f,
        voiceVolume = d.soundVoiceSeek.progress / 100f,
        voiceOn = d.soundVoiceSwitch.isChecked,
        essentialOnly = d.soundEssentialSwitch.isChecked,
        vibration = d.soundVibrationSwitch.isChecked,
    )

    /** This phone's sound settings: what [save] keeps and [cancel] puts back (Test saves as it goes). */
    class Local(d: SoundSettingsSectionBinding, private val container: AppContainer) {
        private val start = container.audioSettings.takeIf { it != AudioSettings() } ?: container.userPreferences.audioSettings()
        private val section = SoundSettingsSection(d, start) { settings ->
            // Heard as set: saved first, then a few sounds
            container.saveAudioSettings(settings)
            listOf(SessionSound.SHOT, SessionSound.DROP_INCOMING, SessionSound.SECURED).forEachIndexed { i, sound ->
                d.root.postDelayed({ container.soundEffects.play(sound) }, i * TEST_GAP_MILLIS)
            }
            if (settings.vibration) container.haptics.vibrate()
        }

        init {
            // Only an admin can talk to everyone, and only with voice chat on
            d.voiceEveryoneSwitch.isVisible = container.players.isAdmin && container.voiceChat.enabled.value
            d.voiceEveryoneSwitch.isChecked = container.voiceToEveryone
        }

        private val voiceSwitch = d.voiceEveryoneSwitch

        fun save() {
            container.saveAudioSettings(section.read())
            if (voiceSwitch.isVisible) container.voiceToEveryone = voiceSwitch.isChecked
        }

        fun cancel() = container.saveAudioSettings(start)
    }

    companion object {
        private const val TEST_GAP_MILLIS = 900L
    }
}

/** The sound settings on their own. */
object SoundDialog {

    fun show(context: Context, container: AppContainer) {
        val d = DialogSoundBinding.inflate(LayoutInflater.from(context))
        val section = SoundSettingsSection.Local(d.soundSection, container)
        AlertDialog.Builder(context, R.style.AppDialogTheme)
            .setView(d.root)
            .setPositiveButton(R.string.field_dialog_save) { _, _ -> section.save() }
            .setNegativeButton(R.string.field_dialog_cancel) { _, _ -> section.cancel() }
            .show()
    }
}
