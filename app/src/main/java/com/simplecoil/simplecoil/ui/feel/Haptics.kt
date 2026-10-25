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

package com.simplecoil.simplecoil.ui.feel

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

/**
 * The app's taps and buzzes, played on the vibration motor itself. A view's haptic feedback
 * goes through Android's "touch feedback" setting, which many phones ship turned off or map to
 * nothing for apps' buttons; this plays whenever the phone can vibrate and the player has
 * vibration on in SimpleCoil's sound settings. Phones that have them get Android's own crisp
 * click effects (10+); older ones a short pulse.
 */
object Haptics {
    private const val TAG = "Haptics"

    private var vibrator: Vibrator? = null

    fun init(context: Context) {
        val app = context.applicationContext
        vibrator = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (app.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                app.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
        } catch (e: Exception) {
            Log.e(TAG, "No vibrator: " + e.message)
            null
        }?.takeIf { it.hasVibrator() }
    }

    /** A button clicked: a light, short tick. */
    fun click() = play(VibrationEffect.EFFECT_CLICK, pulseMillis = 14, amplitude = 140)

    /** A big moment: a game starting or ending. */
    fun heavy() = play(VibrationEffect.EFFECT_HEAVY_CLICK, pulseMillis = 45, amplitude = 255)

    /** Something worked (connected, joined): two quick taps, the second firmer. */
    fun success() = waveform(longArrayOf(0, 18, 70, 28), intArrayOf(0, 150, 0, 255))

    /** Something was lost (disconnected, offline): a longer, lower buzz, twice. */
    fun failure() = waveform(longArrayOf(0, 70, 60, 70), intArrayOf(0, 200, 0, 200))

    private fun play(effect: Int, pulseMillis: Long, amplitude: Int) {
        val v = vibrator ?: return
        try {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> v.vibrate(VibrationEffect.createPredefined(effect))
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ->
                    v.vibrate(VibrationEffect.createOneShot(pulseMillis, if (v.hasAmplitudeControl()) amplitude else VibrationEffect.DEFAULT_AMPLITUDE))
                else -> @Suppress("DEPRECATION") v.vibrate(pulseMillis)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Vibrate failed: " + e.message)
        }
    }

    private fun waveform(timings: LongArray, amplitudes: IntArray) {
        val v = vibrator ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(if (v.hasAmplitudeControl()) VibrationEffect.createWaveform(timings, amplitudes, -1)
                    else VibrationEffect.createWaveform(timings, -1))
            } else {
                @Suppress("DEPRECATION") v.vibrate(timings, -1)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Vibrate failed: " + e.message)
        }
    }
}
