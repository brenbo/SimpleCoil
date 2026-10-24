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

import android.content.SharedPreferences
import android.transition.ChangeBounds
import android.transition.Fade
import android.transition.TransitionManager
import android.transition.TransitionSet
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.ImageButton
import android.widget.LinearLayout
import androidx.core.content.edit
import androidx.core.view.isVisible
import com.simplecoil.simplecoil.R
import kotlin.math.roundToInt

/**
 * The panes of a wide server screen, side by side as with split screen: the settings, the live
 * map (if the window is wide enough) and the players. Dragging a divider sizes the pane at the
 * window's edge (the settings or the players) and the map takes what's left; dragged far enough
 * over, a pane pops shut, and dragged back it pops open again, never squashed below a usable
 * width. A closed pane leaves its divider at the edge as an orange tab that a tap or a drag
 * brings it back with.
 *
 * Filling the window is the same thing: the map's full-screen button closes the settings and
 * the players, and the players' closes the settings and the map. So closing both by hand
 * shows the map's button as full screen too, and the tabs are there to pull a pane back out.
 * What's closed, and the widths, are kept for next time.
 */
class ServerPanes(
    private val row: LinearLayout,
    private val settings: View,
    private val map: View?,
    private val players: View,
    private val left: PaneDivider,
    private val right: PaneDivider?,
    private val mapButton: ImageButton?,
    private val playersButton: ImageButton?,
    private val prefs: SharedPreferences,
    /** Whether a pane now fills the window, so Back can leave it. */
    private val onFullChanged: (Boolean) -> Unit,
) {
    private val density = row.resources.displayMetrics.density
    private val min = (MIN_PANE_DP * density).roundToInt()

    private val closed = mutableSetOf<View>()

    /** What was closed before a full-screen button, to go back to. */
    private var beforeFull: Set<View>? = null

    /** The widths the settings and the players have beside the map; 0 for the settings shares evenly with the players. */
    private var settingsWidth = 0
    private var playersWidth = 0

    val mapFull get() = map != null && settings in closed && players in closed
    val playersFull get() = settings in closed && (map == null || map in closed)

    init {
        settingsWidth = (settings.layoutParams as LinearLayout.LayoutParams).let { if (it.weight > 0f) 0 else it.width }
        playersWidth = (players.layoutParams as LinearLayout.LayoutParams).width
        prefs.getInt(SETTINGS_DP, 0).takeIf { it > 0 }?.let { settingsWidth = px(it).coerceAtLeast(min) }
        prefs.getInt(PLAYERS_DP, 0).takeIf { it > 0 && map != null }?.let { playersWidth = px(it).coerceAtLeast(min) }
        if (prefs.getBoolean(SETTINGS_CLOSED, false)) closed += settings
        if (prefs.getBoolean(PLAYERS_CLOSED, false)) closed += players
        if (map != null && prefs.getBoolean(MAP_CLOSED, false)) closed += map
        if (!valid(closed)) closed.clear()

        Drag(left, sizedAtStart = true)
        right?.let { Drag(it, sizedAtStart = false) }
        mapButton?.setOnClickListener { toggleFull(setOf(settings, players)) }
        playersButton?.setOnClickListener { toggleFull(setOfNotNull(settings, map)) }
        // A window made narrower (a Chromebook's): the set widths still leave the map its room
        row.addOnLayoutChangeListener { _, l, _, r, _, oldL, _, oldR, _ -> if (r - l != oldR - oldL) row.post { apply(animate = false) } }
        apply(animate = false)
    }

    /** The map filling the window, as its button would. */
    fun showMapFull() {
        if (!mapFull) toggleFull(setOf(settings, players))
    }

    /** Back: out of full screen, as the button would. */
    fun leaveFull() {
        when {
            mapFull -> toggleFull(setOf(settings, players))
            playersFull -> toggleFull(setOfNotNull(settings, map))
        }
    }

    private fun toggleFull(close: Set<View>) {
        if (closed == close) {
            // Back as it was, unless it was already this (closed by hand): then everything
            val before = beforeFull?.takeIf { it != close && valid(it) }.orEmpty()
            closed.clear()
            closed += before
            beforeFull = null
        } else {
            beforeFull = closed.toSet()
            closed.clear()
            closed += close
        }
        apply(animate = true)
        save()
    }

    /** A drag or a tap on one of the dividers. */
    private inner class Drag(val divider: PaneDivider, val sizedAtStart: Boolean) {
        /** Where the finger has the sized pane's edge, unclamped. */
        private var target = 0f
        /** The sized pane's width when the drag began, for it to come back to if it pops shut. */
        private var startWidth = 0f
        private var room = 0
        private lateinit var sized: View
        private lateinit var rest: View

        init {
            divider.onDragStart = ::start
            divider.onDrag = { dx -> move(if (sizedAtStart) dx else -dx) }
            divider.onDragEnd = { beforeFull = null; save() }
            divider.setOnClickListener { reopen() }
        }

        private fun start() {
            // The left divider sizes the settings against the map (or, with the map closed or
            // none, the players); the right one the players against the map
            sized = if (sizedAtStart) settings else players
            rest = if (sizedAtStart) map?.takeIf { it !in closed } ?: players else map!!
            room = measureRoom()
            startWidth = widthOf(sized).toFloat()
            target = when {
                sized in closed -> 0f
                rest in closed -> room.toFloat()
                else -> sized.width.toFloat()
            }
        }

        private fun move(by: Float) {
            target += by
            // Shut once the finger's past half the minimum, open again once it's back; either
            // side, short of leaving nothing, or the settings alone beside a closed map
            val was = closed.toSet()
            val next = was.toMutableSet()
            if (target >= min / 2) next -= sized else next += sized
            if (room - target >= min / 2) next -= rest else next += rest
            if (valid(next)) {
                closed.clear()
                closed += next
            }
            // Open, a pane is never narrower than the minimum
            if (sized !in closed && rest !in closed) {
                setWidth(sized, target.coerceIn(min.toFloat(), (room - min).coerceAtLeast(min).toFloat()))
            } else {
                // Shut (or filling the window), it keeps the width it had for when it's back beside the rest
                setWidth(sized, startWidth)
            }
            // Popping shut or open moves the panes; a width just follows the finger
            apply(animate = closed != was)
        }

        /** A tap on a tab brings back the pane on its closed side. */
        private fun reopen() {
            val pane = when (divider.closed) {
                PaneDivider.Closed.START -> if (sizedAtStart) settings else map
                PaneDivider.Closed.END -> players
                PaneDivider.Closed.NONE -> null
            } ?: return
            closed -= pane
            beforeFull = null
            apply(animate = true)
            save()
        }

        /** What the sized pane and the rest share with both open: the row less everything else in it. */
        private fun measureRoom(): Int {
            var room = row.width - row.paddingLeft - row.paddingRight
            for (i in 0 until row.childCount) {
                val child = row.getChildAt(i)
                val lp = child.layoutParams as LinearLayout.LayoutParams
                when {
                    child === sized || child === rest -> room -= lp.leftMargin + lp.rightMargin
                    child.isVisible -> room -= lp.leftMargin + lp.rightMargin + child.width
                }
            }
            return room
        }
    }

    /** The states the screen can be in: never every pane closed, and the map only beside the players. */
    private fun valid(closed: Set<View>): Boolean {
        val open = listOfNotNull(settings, map, players).filter { it !in closed }
        if (open.isEmpty()) return false
        return map == null || map !in closed || players !in closed
    }

    private fun setWidth(pane: View, px: Float) {
        if (pane === settings) settingsWidth = px.roundToInt() else playersWidth = px.roundToInt()
    }

    /**
     * The set widths kept within the window, leaving the pane that takes the rest its minimum:
     * as a pane comes back (the map, beside settings dragged wide while it was closed), or the
     * window gets narrower (a Chromebook's).
     */
    private fun fitWidths() {
        if (row.width == 0) return
        var room = row.width - row.paddingLeft - row.paddingRight
        for (i in 0 until row.childCount) {
            val child = row.getChildAt(i)
            if (child in closed) continue
            val lp = child.layoutParams as LinearLayout.LayoutParams
            room -= lp.leftMargin + lp.rightMargin
            if (child is PaneDivider) room -= lp.width
        }
        val stretch = stretchPane() ?: return
        val sized = listOf(settings, players).filter { it !== stretch && it !in closed && widthOf(it) > 0 }
        var over = sized.sumOf { widthOf(it) } + min - room
        // The widest gives up what it can first, down to the minimum, then the other
        for (pane in sized.sortedByDescending { widthOf(it) }) {
            if (over <= 0) break
            val give = minOf(over, widthOf(pane) - min).coerceAtLeast(0)
            setWidth(pane, (widthOf(pane) - give).toFloat())
            over -= give
        }
    }

    private fun widthOf(pane: View) = if (pane === settings) settingsWidth else playersWidth

    /** The pane that takes what's left: the map, else the players, else the settings. */
    private fun stretchPane(): View? = listOfNotNull(map, players, settings).firstOrNull { it !in closed }

    private fun apply(animate: Boolean) {
        if (animate) TransitionManager.beginDelayedTransition(row, TransitionSet()
            .addTransition(Fade(Fade.OUT))
            .addTransition(ChangeBounds())
            .addTransition(Fade(Fade.IN))
            .setOrdering(TransitionSet.ORDERING_TOGETHER)
            .setDuration(ANIMATION_MILLIS)
            .setInterpolator(PathInterpolator(0.4f, 0f, 0.2f, 1f))) // fast out, slow in
        fitWidths()
        val stretch = stretchPane()
        for (pane in listOfNotNull(settings, map, players)) {
            pane.isVisible = pane !in closed
            val lp = pane.layoutParams as LinearLayout.LayoutParams
            val width = if (pane === stretch || pane === map) 0 else widthOf(pane)
            lp.width = width
            lp.weight = if (width == 0) 1f else 0f
            pane.layoutParams = lp
        }
        left.closed = when {
            settings in closed -> PaneDivider.Closed.START
            map == null && players in closed -> PaneDivider.Closed.END
            else -> PaneDivider.Closed.NONE
        }
        right?.closed = when {
            map in closed -> PaneDivider.Closed.START
            players in closed -> PaneDivider.Closed.END
            else -> PaneDivider.Closed.NONE
        }
        // The settings' and the map's tabs both at the left edge (the players filling the window): one above the other
        right?.tabOffset = if (left.closed == PaneDivider.Closed.START && right?.closed == PaneDivider.Closed.START) 72 * density else 0f
        fun look(button: ImageButton?, full: Boolean) {
            button?.setImageResource(if (full) R.drawable.ic_sc_fullscreen_exit else R.drawable.ic_sc_fullscreen)
            button?.contentDescription = row.context.getString(if (full) R.string.pane_fullscreen_exit else R.string.pane_fullscreen)
        }
        look(mapButton, mapFull)
        look(playersButton, playersFull)
        onFullChanged(mapFull || playersFull)
    }

    private fun save() = prefs.edit {
        putInt(SETTINGS_DP, if (settingsWidth > 0) (settingsWidth / density).roundToInt() else 0)
        if (map != null) putInt(PLAYERS_DP, (playersWidth / density).roundToInt())
        putBoolean(SETTINGS_CLOSED, settings in closed)
        putBoolean(PLAYERS_CLOSED, players in closed)
        putBoolean(MAP_CLOSED, map != null && map in closed)
    }

    private fun px(dp: Int) = (dp * density).roundToInt()

    private companion object {
        const val MIN_PANE_DP = 320
        const val ANIMATION_MILLIS = 300L
        const val SETTINGS_DP = "settingsDp"
        const val PLAYERS_DP = "playersDp"
        const val SETTINGS_CLOSED = "settingsClosed"
        const val PLAYERS_CLOSED = "playersClosed"
        const val MAP_CLOSED = "mapClosed"
    }
}
