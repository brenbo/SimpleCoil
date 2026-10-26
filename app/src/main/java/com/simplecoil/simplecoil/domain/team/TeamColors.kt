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

package com.simplecoil.simplecoil.domain.team

/** The colour each team is shown in, as ARGB ints. */
object TeamColors {
    const val TEAM_1 = 0xFFD32F2F.toInt() // Red
    const val TEAM_2 = 0xFF2196F3.toInt() // Blue
    const val TEAM_3 = 0xFF4CAF50.toInt() // Green
    const val TEAM_4 = 0xFFFFEB3B.toInt() // Yellow

    /** No colour (fully transparent) for anything that isn't team 1-4. */
    const val NONE = 0

    /**
     * Squad [squad]'s colour (1..64), each its own: the four team colours first, then the rest
     * of the wheel, each a golden angle round from the last, alternately brighter and paler, so
     * neighbouring squads never look alike. [NONE] outside 1..64.
     */
    @JvmStatic
    fun squadColor(squad: Int): Int = when {
        squad in 1..4 -> colorOf(squad)
        squad in 5..64 -> SQUAD_COLORS[squad - 5]
        else -> NONE
    }

    private val SQUAD_COLORS = IntArray(60) { i ->
        // Away from the four team hues' neighbourhood at the start, then spread evenly
        val hue = ((i + 1) * 137.508 + 300.0) % 360.0
        val saturation = if (i % 3 == 2) 0.55 else 0.85
        val value = if (i % 2 == 0) 0.95 else 0.75
        hsv(hue, saturation, value)
    }

    private fun hsv(hue: Double, saturation: Double, value: Double): Int {
        val c = value * saturation
        val x = c * (1 - kotlin.math.abs((hue / 60.0) % 2 - 1))
        val m = value - c
        val (r, g, b) = when ((hue / 60.0).toInt()) {
            0 -> Triple(c, x, 0.0)
            1 -> Triple(x, c, 0.0)
            2 -> Triple(0.0, c, x)
            3 -> Triple(0.0, x, c)
            4 -> Triple(x, 0.0, c)
            else -> Triple(c, 0.0, x)
        }
        fun byte(v: Double) = ((v + m) * 255).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (byte(r) shl 16) or (byte(g) shl 8) or byte(b)
    }

    /** [team]'s colour: teams 1-4, or a squad's (squad teams are 65-128, see TeamLayout); [NONE] otherwise. */
    @JvmStatic
    fun colorOf(team: Int): Int = when (team) {
        1 -> TEAM_1
        2 -> TEAM_2
        3 -> TEAM_3
        4 -> TEAM_4
        in SQUAD_TEAMS -> squadColor(team - SQUAD_TEAMS.first + 1)
        else -> NONE
    }

    /** The team numbers squads play as (TeamLayout.SQUAD_TEAM_BASE + 1..64). */
    private val SQUAD_TEAMS = 65..128
}
