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

import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.drawable.LayerDrawable
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.content.ContextCompat
import com.simplecoil.simplecoil.R

/**
 * Coil, the mascot on an empty screen (no blasters found yet, no servers, no players): a little
 * robot that bobs while it [Mood.SEARCH]es with its magnifying glass, looking side to side, or
 * breathes slowly while it [Mood.SLEEP]s, waiting. It moves only while it's on screen.
 */
class MascotView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : AppCompatImageView(context, attrs) {

    enum class Mood { SEARCH, SLEEP }

    var mood: Mood = Mood.SEARCH
        set(value) {
            field = value
            setImageDrawable(LayerDrawable(arrayOf(
                ContextCompat.getDrawable(context, R.drawable.mascot_body),
                ContextCompat.getDrawable(context, if (value == Mood.SEARCH) R.drawable.mascot_search else R.drawable.mascot_sleep),
            )))
            if (moving != null) start()
        }

    private var moving: Animator? = null

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO // the words beside it say what's going on
        val a = context.obtainStyledAttributes(attrs, R.styleable.MascotView)
        mood = Mood.entries[a.getInt(R.styleable.MascotView_mood, 0)]
        a.recycle()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible) start() else stop()
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    private fun start() {
        stop()
        val dp = resources.displayMetrics.density
        pivotY = height.takeIf { it > 0 }?.toFloat() ?: (100 * dp)
        val bob = loop(ObjectAnimator.ofFloat(this, View.TRANSLATION_Y, 0f, -6 * dp), if (mood == Mood.SEARCH) 900L else 1800L)
        val second = if (mood == Mood.SEARCH) {
            // Looking one way, then the other
            loop(ObjectAnimator.ofFloat(this, View.ROTATION, -7f, 7f), 1400L)
        } else {
            // Slow breaths
            AnimatorSet().apply {
                playTogether(
                    loop(ObjectAnimator.ofFloat(this@MascotView, View.SCALE_X, 1f, 1.04f), 1800L),
                    loop(ObjectAnimator.ofFloat(this@MascotView, View.SCALE_Y, 1f, 0.97f), 1800L),
                )
            }
        }
        moving = AnimatorSet().apply {
            playTogether(bob, second)
            start()
        }
    }

    private fun stop() {
        moving?.cancel()
        moving = null
        translationY = 0f
        rotation = 0f
        scaleX = 1f
        scaleY = 1f
    }

    private fun loop(animator: ObjectAnimator, millis: Long) = animator.apply {
        duration = millis
        repeatMode = ValueAnimator.REVERSE
        repeatCount = ValueAnimator.INFINITE
        interpolator = AccelerateDecelerateInterpolator()
    }
}
