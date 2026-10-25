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

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import androidx.core.view.isVisible

/**
 * The app's moves, so they're the same everywhere: one screen handing over to another, a value
 * that changed popping, and the feel of what just happened (connected, started, lost).
 */
object Motion {
    private val EASE = PathInterpolator(0.2f, 0f, 0f, 1f) // Material's emphasized decelerate
    private const val SWAP_OUT_MILLIS = 160L
    private const val SWAP_IN_MILLIS = 320L
    private const val POP_MILLIS = 380L

    /**
     * [from] hands over to [to]: [from] fades and sinks away, then [to] rises into place. With
     * [from] already hidden it just shows [to], rising too, unless [animate] is off.
     */
    fun swap(from: View, to: View, animate: Boolean = true) {
        if (to.isVisible && !from.isVisible) return
        val lift = 24 * to.resources.displayMetrics.density
        if (!animate) {
            from.isVisible = false
            to.isVisible = true
            return
        }
        val bringIn = {
            to.alpha = 0f
            to.translationY = lift
            to.isVisible = true
            to.animate().alpha(1f).translationY(0f).setDuration(SWAP_IN_MILLIS).setInterpolator(EASE).start()
        }
        if (!from.isVisible) {
            bringIn()
            return
        }
        from.animate().alpha(0f).scaleX(0.97f).scaleY(0.97f).setDuration(SWAP_OUT_MILLIS).withEndAction {
            from.isVisible = false
            from.alpha = 1f
            from.scaleX = 1f
            from.scaleY = 1f
            bringIn()
        }.start()
    }

    /** [view] swells and springs back: a value on it just changed. */
    fun pop(view: View, by: Float = 1.18f) {
        view.animate().cancel()
        view.scaleX = by
        view.scaleY = by
        view.animate().scaleX(1f).scaleY(1f).setDuration(POP_MILLIS).setInterpolator(OvershootInterpolator(3f)).start()
    }

    /** Something worked: connected, joined, a game started. */
    fun success(view: View) = feel(view, if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.LONG_PRESS)

    /** Something was lost or refused: disconnected, offline. */
    fun failure(view: View) = feel(view, if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS)

    /** A big moment: the game starting or ending. */
    fun heavy(view: View) = feel(view, HapticFeedbackConstants.LONG_PRESS)

    private fun feel(view: View, constant: Int) {
        if (TouchFeel.vibrationEnabled()) view.performHapticFeedback(constant)
    }
}
