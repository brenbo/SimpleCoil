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
import android.graphics.Path
import android.graphics.RectF
import android.os.Build
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.PointerIcon
import android.view.View
import android.view.ViewConfiguration
import androidx.core.content.ContextCompat
import com.simplecoil.simplecoil.R
import kotlin.math.abs

/**
 * The handle between two panes of a wide server screen: drag it sideways to make one wider and
 * the other narrower, as with split screen, or all the way over to close one. A short rounded
 * grip in the middle shows it's there; beside a closed pane it becomes an orange tab with an
 * arrow, which a tap or a drag pulls the pane back out of. With a mouse (a Chromebook) the
 * pointer becomes a left-right arrow over it.
 */
class PaneDivider @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    /** Which side's pane is closed, so the tab's arrow points the way it comes back out. */
    enum class Closed { NONE, START, END }

    /** A drag has begun, before its first [onDrag]. */
    var onDragStart: (() -> Unit)? = null

    /** Hears each drag step: how far, in pixels, the divider moved (right is positive). */
    var onDrag: ((Float) -> Unit)? = null

    /** The drag is over: keep where it ended up. */
    var onDragEnd: (() -> Unit)? = null

    var closed = Closed.NONE
        set(value) {
            field = value
            contentDescription = context.getString(if (value == Closed.NONE) R.string.pane_divider else R.string.pane_divider_closed)
            invalidate()
        }

    /** How far below the middle the tab sits, so two tabs at the same edge read as two. */
    var tabOffset = 0f
        set(value) {
            field = value
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val grip = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.sc_text_secondary) }
    private val tab = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.sc_orange) }
    private val arrow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.sc_on_orange)
        style = Paint.Style.STROKE
        strokeWidth = 2 * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val gripRect = RectF()
    private val arrowPath = Path()
    private var downX = 0f
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
        val cx = width / 2f
        val cy = height / 2f + if (closed != Closed.NONE) tabOffset else 0f
        if (closed != Closed.NONE) {
            // A tab with an arrow pointing away from the edge the pane went into
            val half = 6 * density
            gripRect.set(cx - half, cy - 28 * density, cx + half, cy + 28 * density)
            canvas.drawRoundRect(gripRect, half, half, tab)
            val dir = if (closed == Closed.START) 1 else -1
            val reach = 2.5f * density
            arrowPath.reset()
            arrowPath.moveTo(cx - dir * reach, cy - 2 * reach)
            arrowPath.lineTo(cx + dir * reach, cy)
            arrowPath.lineTo(cx - dir * reach, cy + 2 * reach)
            canvas.drawPath(arrowPath, arrow)
            return
        }
        val gripWidth = (if (dragging) 6 else 4) * density
        val gripHeight = 48 * density
        gripRect.set(cx - gripWidth / 2, cy - gripHeight / 2, cx + gripWidth / 2, cy + gripHeight / 2)
        grip.alpha = if (dragging) 255 else 150
        canvas.drawRoundRect(gripRect, gripWidth / 2, gripWidth / 2, grip)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                lastX = event.rawX
                dragging = false
                isPressed = true
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                // A drag once it's gone far enough not to be a tap
                if (!dragging && abs(event.rawX - downX) > touchSlop) {
                    dragging = true
                    onDragStart?.invoke()
                    invalidate()
                }
                val dx = event.rawX - lastX
                if (dragging && dx != 0f) {
                    lastX = event.rawX
                    onDrag?.invoke(dx)
                }
            }
            MotionEvent.ACTION_UP -> {
                isPressed = false
                if (dragging) endDrag() else performClick()
            }
            MotionEvent.ACTION_CANCEL -> {
                isPressed = false
                if (dragging) endDrag()
            }
        }
        return true
    }

    private fun endDrag() {
        dragging = false
        invalidate()
        onDragEnd?.invoke()
    }
}
