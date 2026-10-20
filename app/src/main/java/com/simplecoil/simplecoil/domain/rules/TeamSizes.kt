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

package com.simplecoil.simplecoil.domain.rules

import com.simplecoil.simplecoil.data.repository.GameSettingsRepository

/**
 * The team size dialog's rules, shared by the play screen and the dedicated server. Pure. The
 * sizes always add up to every player slot: each team but the last is set, and the last team
 * gets the rest, never fewer than one.
 */
object TeamSizes {

    private val SLOTS = GameSettingsRepository.MAX_PLAYER_ID.toInt()

    /**
     * [sizes] with [team] (0-based, not the last) set to [size], the last team taking up or
     * giving back the difference. [size] is held to what leaves every other team at least one.
     */
    fun adjust(sizes: IntArray, team: Int, size: Int): IntArray {
        val result = sizes.copyOf()
        val last = result.lastIndex
        if (team !in 0 until last) return result
        val others = result.indices.filter { it != team && it != last }.sumOf { result[it] }
        result[team] = size.coerceIn(1, SLOTS - others - 1)
        result[last] = SLOTS - others - result[team]
        return result
    }

    /** [teamCount] teams as even as they go, the first teams one bigger when it doesn't divide. */
    fun even(teamCount: Int): IntArray = IntArray(teamCount) { SLOTS / teamCount + if (it < SLOTS % teamCount) 1 else 0 }

    /** The most players one team but the last can have: everyone else keeps one. */
    fun maxSize(teamCount: Int): Int = SLOTS - (teamCount - 1)
}
