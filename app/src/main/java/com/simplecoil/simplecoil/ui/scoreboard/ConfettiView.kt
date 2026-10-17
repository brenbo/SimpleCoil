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

package com.simplecoil.simplecoil.ui.scoreboard

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.graphics.withRotation
import kotlin.random.Random

/** A burst of confetti over the Victory banner: bits fly up and out, then tumble down and fade. */
class ConfettiView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private class Bit(val x: Float, val vx: Float, val vy: Float, val spin: Float, val color: Int, val size: Float)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var bits = emptyList<Bit>()
    private var t = 0f
    private var animator: ValueAnimator? = null

    fun burst() {
        val density = resources.displayMetrics.density
        val random = Random(System.nanoTime())
        bits = List(COUNT) {
            Bit(
                x = width * (0.3f + random.nextFloat() * 0.4f),
                vx = (random.nextFloat() - 0.5f) * width * 1.2f,
                vy = -(0.6f + random.nextFloat() * 0.8f) * height,
                spin = (random.nextFloat() - 0.5f) * 1440f,
                color = COLORS[random.nextInt(COLORS.size)],
                size = (4 + random.nextFloat() * 4) * density,
            )
        }
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = DURATION_MS
            interpolator = LinearInterpolator()
            addUpdateListener {
                t = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    /**
     * No size of its own: it fills what the banner beside it takes. (By default a view asks for
     * all the room it is offered, which made the scoreboard's banner screen-tall and pushed the
     * player list out of sight.)
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(resolveSize(0, widthMeasureSpec), resolveSize(0, heightMeasureSpec))
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        if (bits.isEmpty() || t >= 1f) return
        val seconds = t * DURATION_MS / 1000f
        for (bit in bits) {
            val x = bit.x + bit.vx * seconds
            // Thrown up from the middle, then falling
            val y = height * 0.6f + bit.vy * seconds + GRAVITY * height * seconds * seconds
            paint.color = bit.color
            paint.alpha = ((1f - t) * 255).toInt()
            canvas.withRotation(bit.spin * seconds, x, y) {
                drawRect(x - bit.size / 2, y - bit.size / 4, x + bit.size / 2, y + bit.size / 4, paint)
            }
        }
    }

    private companion object {
        const val COUNT = 70
        const val DURATION_MS = 2_400L
        const val GRAVITY = 1.1f
        val COLORS = intArrayOf(0xFFFFD54F.toInt(), 0xFFFF7043.toInt(), 0xFF4FC3F7.toInt(), 0xFF81C784.toInt(),
            0xFFBA68C8.toInt(), 0xFFFFFFFF.toInt())
    }
}
