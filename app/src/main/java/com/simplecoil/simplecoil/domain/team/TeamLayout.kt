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

import com.simplecoil.simplecoil.data.repository.GameSettingsRepository
import com.simplecoil.simplecoil.data.repository.PlayerRepository

/**
 * The team mode, the team sizes and the squads, which always change together. Immutable, so a
 * reader that takes one instance sees a consistent layout even while another thread swaps in a
 * new one (STATE_CONSOLIDATION_DESIGN.md §3.3).
 *
 * Player ids 1..[MAX_PLAYER_ID] (64) are dealt out to the teams in order: with sizes [16, 16, 16, 16],
 * ids 1-16 are team 1, 17-32 team 2, and so on. Free for all and squads keep a two-team size
 * array, as the protocol always has, so older clients and servers read it the same way.
 *
 * Squads: [squads] maps each squad's number (1..[MAX_SQUADS], its colour) to its players. A
 * squad plays as a team numbered [SQUAD_TEAM_BASE] + its number (65..128), so it never meets a
 * player number; a player in no squad is their own team, their id, as in free for all.
 */
class TeamLayout private constructor(
    val mode: TeamMode,
    private val sizes: IntArray,
    /** Squad number → its players; empty outside [TeamMode.SQUADS]. */
    val squads: Map<Int, Set<Int>> = emptyMap(),
) {

    /** A copy of the team sizes; free for all and squads report the two-team sizes they carry. */
    fun teamSizes(): IntArray = sizes.copyOf()

    /** The team [playerId] is on (1-based), the id itself in free for all (or alone, in squads), or INVALID_PLAYER_ID. */
    fun teamOf(playerId: Int): Int {
        if (playerId < 1 || playerId > MAX_PLAYER_ID) return PlayerRepository.INVALID_PLAYER_ID
        if (mode.isFreeForAll) return playerId
        if (mode.isSquads) return squadOf(playerId)?.let { SQUAD_TEAM_BASE + it } ?: playerId
        val numTeams = mode.teamCount
        var lastIdOnTeam = 0
        for (t in 0 until numTeams) {
            lastIdOnTeam += sizes[t]
            if (playerId <= lastIdOnTeam) return t + 1
        }
        return numTeams
    }

    fun teamOf(playerId: Byte): Int = teamOf(playerId.toInt())

    /** The squad [playerId] is in, or null. */
    fun squadOf(playerId: Int): Int? = squads.entries.firstOrNull { playerId in it.value }?.key

    /** The colour [team] is shown in: a team's, a squad's, or [TeamColors.NONE] for a player alone. */
    fun colorOf(team: Int): Int = when {
        mode.isSquads -> if (team > SQUAD_TEAM_BASE) TeamColors.squadColor(team - SQUAD_TEAM_BASE) else TeamColors.NONE
        mode.isFreeForAll -> TeamColors.NONE
        else -> TeamColors.colorOf(team)
    }

    /** These squads ([squads]); only players 1..64, each in one squad at most, up to [MAX_SQUADS], none empty. */
    fun withSquads(newSquads: Map<Int, Set<Int>>): TeamLayout {
        if (!mode.isSquads) return this
        val seen = HashSet<Int>()
        val clean = newSquads.filterKeys { it in 1..MAX_SQUADS }.toSortedMap().mapValues { (_, members) ->
            members.filter { it in 1..MAX_PLAYER_ID && seen.add(it) }.toSortedSet()
        }.filterValues { it.isNotEmpty() }
        return TeamLayout(mode, sizes, clean)
    }

    /** The first player id on [team]. */
    fun teamStartId(team: Int): Int {
        var startId = 1
        var t = 1
        while (t < team && t <= sizes.size) {
            startId += sizes[t - 1]
            t++
        }
        return startId
    }

    /** The last player id on [team], or [MAX_PLAYER_ID] for a team that doesn't exist. */
    fun teamEndId(team: Int): Int {
        if (team < 1 || team > sizes.size) return MAX_PLAYER_ID
        return teamStartId(team) + sizes[team - 1] - 1
    }

    /** This layout in [newMode]: unchanged if the mode is the same, otherwise the default sizes. */
    fun withMode(newMode: TeamMode): TeamLayout = if (newMode == mode) this else defaultFor(newMode)

    /**
     * This mode with [newSizes]. Null keeps the current sizes. Sizes that don't fit the mode (wrong
     * team count, or not adding up to [MAX_PLAYER_ID]) fall back to the defaults.
     */
    fun withSizes(newSizes: IntArray?): TeamLayout {
        if (newSizes == null) return this
        if (!isValid(mode, newSizes)) return defaultFor(mode)
        return TeamLayout(mode, newSizes.copyOf(), squads)
    }

    override fun equals(other: Any?): Boolean =
        other is TeamLayout && other.mode == mode && other.sizes.contentEquals(sizes) && other.squads == squads

    override fun hashCode(): Int = (31 * mode.hashCode() + sizes.contentHashCode()) * 31 + squads.hashCode()

    override fun toString(): String = "TeamLayout($mode, ${sizes.contentToString()}${if (squads.isEmpty()) "" else ", $squads"})"

    companion object {
        private const val MAX_PLAYER_ID = GameSettingsRepository.MAX_PLAYER_ID.toInt()

        /** As many squads as players: everyone could be in one of their own. */
        const val MAX_SQUADS = 64

        /** A squad's team number is this plus the squad's, clear of every player number. */
        const val SQUAD_TEAM_BASE = 64

        @JvmField val DEFAULT = defaultFor(TeamMode.DEFAULT)

        @JvmStatic
        fun defaultFor(mode: TeamMode): TeamLayout =
            when (mode) {
                TeamMode.FOUR_TEAMS -> TeamLayout(mode, intArrayOf(16, 16, 16, 16))
                TeamMode.THREE_TEAMS -> TeamLayout(mode, intArrayOf(22, 21, 21))
                else -> TeamLayout(mode, intArrayOf(32, 32))
            }

        /** [mode] with [sizes] (see [withSizes]). */
        @JvmStatic
        fun of(mode: TeamMode, sizes: IntArray?): TeamLayout = defaultFor(mode).withSizes(sizes)

        private fun isValid(mode: TeamMode, sizes: IntArray): Boolean {
            val expectedTeams = if (mode.teamCount > 0) mode.teamCount else 2
            return sizes.size == expectedTeams && sizes.sum() == MAX_PLAYER_ID
        }
    }
}
