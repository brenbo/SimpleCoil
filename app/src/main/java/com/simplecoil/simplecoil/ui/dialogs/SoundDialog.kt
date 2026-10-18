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

/**
 * This phone's sound settings (map design: Audio): how loud the effects and the radio callouts
 * are, callouts on, off or essential only, and vibration. Test plays a shot, a drop coming in
 * and a capture, with what is set. Shown in the player settings, or on its own ([show]) when the
 * server doesn't allow player settings.
 */
class SoundSettingsSection(private val d: SoundSettingsSectionBinding, private val container: AppContainer) {

    /** What was saved when the section opened: Cancel puts it back (Test saves as it goes). */
    private val start = container.audioSettings.takeIf { it != AudioSettings() } ?: container.userPreferences.audioSettings()

    init {
        d.soundEffectsSeek.progress = (start.effectsVolume * 100).toInt()
        d.soundVoiceSeek.progress = (start.voiceVolume * 100).toInt()
        d.soundVoiceSwitch.isChecked = start.voiceOn
        d.soundEssentialSwitch.isChecked = start.essentialOnly
        d.soundVibrationSwitch.isChecked = start.vibration
        showVoice()
        d.soundVoiceSwitch.setOnCheckedChangeListener { _, _ -> showVoice() }
        d.soundTestButton.setOnClickListener {
            // Heard as set: saved first, then a few sounds
            save()
            listOf(SessionSound.SHOT, SessionSound.DROP_INCOMING, SessionSound.SECURED).forEachIndexed { i, sound ->
                d.root.postDelayed({ container.soundEffects.play(sound) }, i * TEST_GAP_MILLIS)
            }
            container.haptics.vibrate()
        }
    }

    private fun showVoice() {
        d.soundVoiceTitle.isVisible = d.soundVoiceSwitch.isChecked
        d.soundVoiceSeek.isVisible = d.soundVoiceSwitch.isChecked
        d.soundEssentialSwitch.isVisible = d.soundVoiceSwitch.isChecked
    }

    fun save() = container.saveAudioSettings(AudioSettings(
        effectsVolume = d.soundEffectsSeek.progress / 100f,
        voiceVolume = d.soundVoiceSeek.progress / 100f,
        voiceOn = d.soundVoiceSwitch.isChecked,
        essentialOnly = d.soundEssentialSwitch.isChecked,
        vibration = d.soundVibrationSwitch.isChecked,
    ))

    fun cancel() = container.saveAudioSettings(start)

    companion object {
        private const val TEST_GAP_MILLIS = 900L
    }
}

/** The sound settings on their own. */
object SoundDialog {

    fun show(context: Context, container: AppContainer) {
        val d = DialogSoundBinding.inflate(LayoutInflater.from(context))
        val section = SoundSettingsSection(d.soundSection, container)
        AlertDialog.Builder(context, R.style.AppDialogTheme)
            .setView(d.root)
            .setPositiveButton(R.string.field_dialog_save) { _, _ -> section.save() }
            .setNegativeButton(R.string.field_dialog_cancel) { _, _ -> section.cancel() }
            .show()
    }
}
