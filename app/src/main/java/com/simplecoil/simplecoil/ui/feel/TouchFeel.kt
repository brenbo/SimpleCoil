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

import android.app.Activity
import android.app.Application
import android.app.Dialog
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.Window
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.EditText
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import kotlin.math.abs
import kotlin.math.max

/**
 * How every button feels, on every screen and pop-up, without each one setting it up: pressed,
 * it sinks a little; let go, it springs back; and a tap that's a click (not the start of a
 * scroll or a drag) gives a light tick, when vibration is on in the sound settings.
 *
 * It watches each window's touches ([install] does activities and the pop-ups of dialog
 * fragments; [attach] a dialog built and shown by hand) and acts on the clickable view under the
 * finger, so a button added later gets it too.
 */
object TouchFeel {

    /** How far a pressed view sinks, in dp across its longest side; a big card sinks less, as a share. */
    private const val SINK_DP = 10f
    private const val MIN_SCALE = 0.94f
    private const val MAX_SCALE = 0.985f
    private const val PRESS_MILLIS = 90L
    private const val RELEASE_MILLIS = 260L

    private var vibrationOn: () -> Boolean = { true }

    /** Every activity's window from now on, and the pop-ups of their dialog fragments. */
    fun install(app: Application, vibrationOn: () -> Boolean) {
        this.vibrationOn = vibrationOn
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                attach(activity.window)
                (activity as? FragmentActivity)?.supportFragmentManager?.registerFragmentLifecycleCallbacks(object : FragmentManager.FragmentLifecycleCallbacks() {
                    override fun onFragmentStarted(fm: FragmentManager, f: Fragment) {
                        (f as? DialogFragment)?.dialog?.window?.let(::attach)
                    }
                }, true)
            }

            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    /** A dialog shown by hand (not a dialog fragment's). */
    fun attach(dialog: Dialog) {
        dialog.window?.let(::attach)
    }

    fun attach(window: Window) {
        val base = window.callback ?: return
        if (base is Feel) return
        window.callback = Feel(window, base)
    }

    /** Whether the player has vibration on in the sound settings. */
    fun vibrationEnabled(): Boolean = vibrationOn()

    /** The light tick of a click, on [view]. */
    fun tick(view: View) {
        if (vibrationOn()) view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    private class Feel(private val window: Window, private val base: Window.Callback) : Window.Callback by base {
        private var target: View? = null
        private var downX = 0f
        private var downY = 0f

        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    target = find(window.decorView, event.rawX, event.rawY)?.also { press(it) }
                    downX = event.rawX
                    downY = event.rawY
                }
                MotionEvent.ACTION_MOVE -> target?.let { view ->
                    // A scroll or a drag that started on it: not a click
                    val slop = ViewConfiguration.get(view.context).scaledTouchSlop
                    if (abs(event.rawX - downX) > slop || abs(event.rawY - downY) > slop) {
                        release(view)
                        target = null
                    }
                }
                MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> target?.let { release(it); target = null }
                MotionEvent.ACTION_UP -> target?.let { view ->
                    release(view)
                    if (view.isEnabled) tick(view)
                    target = null
                }
            }
            return base.dispatchTouchEvent(event)
        }

        private fun press(view: View) {
            if (view.scaleX != 1f || view.scaleY != 1f) return // something else is animating it
            val longest = max(view.width, view.height).takeIf { it > 0 } ?: return
            val sink = SINK_DP * view.resources.displayMetrics.density
            val scale = (1f - sink / longest).coerceIn(MIN_SCALE, MAX_SCALE)
            view.animate().scaleX(scale).scaleY(scale).setDuration(PRESS_MILLIS).setInterpolator(DecelerateInterpolator()).start()
        }

        private fun release(view: View) {
            view.animate().scaleX(1f).scaleY(1f).setDuration(RELEASE_MILLIS).setInterpolator(OvershootInterpolator(2.5f)).start()
        }

        /** The deepest clickable view under the point, if any. */
        private fun find(view: View, x: Float, y: Float): View? {
            if (view.visibility != View.VISIBLE || view.alpha == 0f || !contains(view, x, y)) return null
            if (view is ViewGroup) {
                for (i in view.childCount - 1 downTo 0) {
                    find(view.getChildAt(i), x, y)?.let { return it }
                }
            }
            return view.takeIf { it.isClickable && it.isEnabled && it !is EditText }
        }

        private val spot = IntArray(2)

        private fun contains(view: View, x: Float, y: Float): Boolean {
            view.getLocationOnScreen(spot)
            return x >= spot[0] && x < spot[0] + view.width * view.scaleX && y >= spot[1] && y < spot[1] + view.height * view.scaleY
        }
    }
}
