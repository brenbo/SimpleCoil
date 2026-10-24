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

package com.simplecoil.simplecoil.ui.server

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.PointerIcon
import android.view.View
import androidx.core.content.ContextCompat
import com.simplecoil.simplecoil.R

/**
 * The handle between two panes of a wide server screen: drag it sideways to make one wider and
 * the other narrower, as with split screen. A short rounded grip in the middle shows it's there;
 * with a mouse (a Chromebook) the pointer becomes a left-right arrow over it.
 */
class PaneDivider @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    /** Hears each drag step: how far, in pixels, the divider moved (right is positive). */
    var onDrag: ((Float) -> Unit)? = null

    /** The drag is over: keep where it ended up. */
    var onDragEnd: (() -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val grip = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.sc_text_secondary) }
    private val gripRect = RectF()
    private var lastX = 0f
    private var dragging = false

    init {
        isClickable = true
        contentDescription = context.getString(R.string.pane_divider)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            pointerIcon = PointerIcon.getSystemIcon(context, PointerIcon.TYPE_HORIZONTAL_DOUBLE_ARROW)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val gripWidth = (if (dragging) 6 else 4) * density
        val gripHeight = 48 * density
        val cx = width / 2f
        val cy = height / 2f
        gripRect.set(cx - gripWidth / 2, cy - gripHeight / 2, cx + gripWidth / 2, cy + gripHeight / 2)
        grip.alpha = if (dragging) 255 else 150
        canvas.drawRoundRect(gripRect, gripWidth / 2, gripWidth / 2, grip)
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility") // a drag handle: nothing to click
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.rawX
                dragging = true
                parent?.requestDisallowInterceptTouchEvent(true)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - lastX
                if (dx != 0f) {
                    lastX = event.rawX
                    onDrag?.invoke(dx)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                invalidate()
                onDragEnd?.invoke()
            }
        }
        return true
    }
}
