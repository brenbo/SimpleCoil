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
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.simplecoil.simplecoil.R
import com.simplecoil.simplecoil.domain.team.TeamMode
import com.simplecoil.simplecoil.ui.feel.Motion
import com.simplecoil.simplecoil.ui.shortName

/**
 * The server's team mode, one at a time between two arrows: an arrow swipes the name out to
 * that side and the next one in. A mode the game mode can't be played in still swipes past,
 * greyed out, so it's seen, and the picker goes on to the next one it can be played in.
 */
class TeamModePicker @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : LinearLayout(context, attrs) {

    /** The modes, in the order the arrows go through them. */
    var modes: List<TeamMode> = listOf(TeamMode.TWO_TEAMS, TeamMode.THREE_TEAMS, TeamMode.FOUR_TEAMS, TeamMode.FREE_FOR_ALL, TeamMode.SQUADS)

    /** Whether [TeamMode] can be picked now. */
    var isAllowed: (TeamMode) -> Boolean = { true }

    /** A new mode was picked with the arrows. */
    var onPick: (TeamMode) -> Unit = {}

    private val density = resources.displayMetrics.density
    private val prev = arrow(R.drawable.ic_picker_left, R.string.cd_team_mode_previous) { step(-1) }
    private val next = arrow(R.drawable.ic_picker_right, R.string.cd_team_mode_next) { step(1) }
    private val stage = FrameLayout(context).apply { clipChildren = true }
    private var shown = label()
    private var current: TeamMode? = null

    /** Stepping past greyed-out modes: a press is being played out. */
    private var stepping = false

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundResource(R.drawable.bg_team_mode_picker)
        addView(prev, LayoutParams((48 * density).toInt(), (48 * density).toInt()))
        stage.addView(shown, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        addView(stage, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(next, LayoutParams((48 * density).toInt(), (48 * density).toInt()))
    }

    /** Shows [mode] (the server's), without a swipe unless it changed from one shown before. */
    fun show(mode: TeamMode, enabled: Boolean) {
        prev.isEnabled = enabled
        next.isEnabled = enabled
        prev.alpha = if (enabled) 1f else 0.35f
        next.alpha = if (enabled) 1f else 0.35f
        if (stepping) return
        val was = current
        current = mode
        if (was == null || was == mode) {
            style(shown, mode)
        } else {
            // Changed from elsewhere (a preset, a game mode): swipe the way it lies
            swipeTo(mode, if (modes.indexOf(mode) > modes.indexOf(was)) 1 else -1)
        }
    }

    private fun step(direction: Int) {
        val from = current ?: return
        if (stepping) return
        val count = modes.size
        val start = modes.indexOf(from)
        // The next one it can be played in, that way round; none: a little nudge, nothing else
        val target = (1 until count).map { modes[Math.floorMod(start + it * direction, count)] }.firstOrNull(isAllowed)
        if (target == null) {
            shown.animate().translationX(direction * 12 * density).setDuration(90).withEndAction {
                shown.animate().translationX(0f).setDuration(160).start()
            }.start()
            return
        }
        stepping = true
        var index = start
        fun nextStop() {
            index = Math.floorMod(index + direction, count)
            val mode = modes[index]
            swipeTo(mode, direction) {
                if (mode == target) {
                    stepping = false
                    current = target
                    onPick(target)
                } else {
                    // Greyed out: shown for a moment, then on past it
                    postDelayed({ nextStop() }, PASS_MILLIS)
                }
            }
        }
        nextStop()
    }

    /** The shown name swipes out to the [direction] side, [mode]'s in from the other. */
    private fun swipeTo(mode: TeamMode, direction: Int, then: () -> Unit = {}) {
        val out = shown
        val incoming = label().also { style(it, mode) }
        val distance = stage.width.coerceAtLeast((120 * density).toInt()).toFloat()
        stage.addView(incoming, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        shown = incoming
        incoming.translationX = -direction * distance
        incoming.alpha = 0f
        out.animate().translationX(direction * distance).alpha(0f).setDuration(SWIPE_MILLIS).setInterpolator(DecelerateInterpolator())
            .withEndAction { stage.removeView(out) }.start()
        incoming.animate().translationX(0f).alpha(1f).setDuration(SWIPE_MILLIS).setInterpolator(DecelerateInterpolator())
            .withEndAction(then).start()
    }

    private fun style(view: TextView, mode: TeamMode) {
        view.text = context.getString(when (mode) {
            TeamMode.FREE_FOR_ALL -> R.string.team_mode_ffa
            else -> mode.shortName
        })
        val allowed = isAllowed(mode)
        view.setTextColor(ContextCompat.getColor(context, if (allowed) R.color.sc_text else R.color.sc_text_muted))
        view.alpha = if (allowed) 1f else 0.6f
        view.contentDescription = if (allowed) view.text else context.getString(R.string.cd_team_mode_unavailable, view.text)
    }

    private fun label() = TextView(context).apply {
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        maxLines = 1
    }

    private fun arrow(icon: Int, description: Int, onClick: () -> Unit) = ImageButton(context).apply {
        setImageResource(icon)
        setColorFilter(ContextCompat.getColor(context, R.color.sc_orange))
        setBackgroundResource(android.R.color.transparent)
        contentDescription = context.getString(description)
        setOnClickListener {
            Motion.pop(this, 1.15f)
            onClick()
        }
    }

    private companion object {
        const val SWIPE_MILLIS = 220L
        const val PASS_MILLIS = 260L
    }
}
