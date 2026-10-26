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

import com.simplecoil.simplecoil.data.repository.PlayerRepository
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TeamLayoutTest {

    @Test
    fun defaults_perMode() {
        assertArrayEquals(intArrayOf(32, 32), TeamLayout.defaultFor(TeamMode.TWO_TEAMS).teamSizes())
        assertArrayEquals(intArrayOf(16, 16, 16, 16), TeamLayout.defaultFor(TeamMode.FOUR_TEAMS).teamSizes())
        // Free for all carries the two-team sizes the protocol has always sent.
        assertArrayEquals(intArrayOf(32, 32), TeamLayout.defaultFor(TeamMode.FREE_FOR_ALL).teamSizes())
        assertEquals(TeamLayout.defaultFor(TeamMode.TWO_TEAMS), TeamLayout.DEFAULT)
    }

    @Test
    fun teamOf_twoTeams() {
        val layout = TeamLayout.defaultFor(TeamMode.TWO_TEAMS)
        assertEquals(1, layout.teamOf(1))
        assertEquals(1, layout.teamOf(32))
        assertEquals(2, layout.teamOf(33))
        assertEquals(2, layout.teamOf(63))
    }

    @Test
    fun teamOf_fourTeams() {
        val layout = TeamLayout.defaultFor(TeamMode.FOUR_TEAMS)
        assertEquals(listOf(1, 1, 2, 2, 3, 3, 4, 4), listOf(1, 16, 17, 32, 33, 48, 49, 63).map { layout.teamOf(it) })
    }

    @Test
    fun teamOf_customSizes_andStartEndIds() {
        val layout = TeamLayout.of(TeamMode.FOUR_TEAMS, intArrayOf(5, 9, 9, 41))
        assertEquals(1, layout.teamOf(5))
        assertEquals(2, layout.teamOf(6))
        assertEquals(6, layout.teamStartId(2))
        assertEquals(14, layout.teamEndId(2))
        assertEquals(24, layout.teamStartId(4))
        assertEquals(64, layout.teamEndId(4))
    }

    @Test
    fun teamOf_freeForAllIsTheIdItself_andInvalidIdsAreRejected() {
        val ffa = TeamLayout.defaultFor(TeamMode.FREE_FOR_ALL)
        assertEquals(7, ffa.teamOf(7))
        assertEquals(PlayerRepository.INVALID_PLAYER_ID, ffa.teamOf(0))
        assertEquals(PlayerRepository.INVALID_PLAYER_ID, TeamLayout.DEFAULT.teamOf(65))
        assertEquals(PlayerRepository.INVALID_PLAYER_ID, TeamLayout.DEFAULT.teamOf(167.toByte())) // grenade id
    }

    @Test
    fun teamEndId_ofAMissingTeamIsTheLastId() {
        assertEquals(64, TeamLayout.DEFAULT.teamEndId(3))
        assertEquals(64, TeamLayout.DEFAULT.teamEndId(0))
    }

    @Test
    fun withSizes_rejectsSizesThatDontFit() {
        val four = TeamLayout.defaultFor(TeamMode.FOUR_TEAMS)
        assertEquals(four, four.withSizes(intArrayOf(32, 32)))          // wrong team count
        assertEquals(four, four.withSizes(intArrayOf(10, 10, 10, 10)))  // doesn't add up to 64
        assertSame(four, four.withSizes(null))                          // null keeps the sizes
    }

    @Test
    fun withMode_keepsSizesOnlyForTheSameMode() {
        val custom = TeamLayout.of(TeamMode.TWO_TEAMS, intArrayOf(40, 23))
        assertSame(custom, custom.withMode(TeamMode.TWO_TEAMS))
        assertEquals(TeamLayout.defaultFor(TeamMode.FOUR_TEAMS), custom.withMode(TeamMode.FOUR_TEAMS))
    }

    @Test
    fun teamSizes_isACopy() {
        val layout = TeamLayout.DEFAULT
        layout.teamSizes()[0] = 1
        assertArrayEquals(intArrayOf(32, 32), layout.teamSizes())
    }

    @Test
    fun wireValues_roundTrip_andUnknownValuesAreRejected() {
        for (mode in TeamMode.entries) assertEquals(mode, TeamMode.fromWire(mode.wireValue))
        assertEquals(listOf(1, 2, 3, 4, 5), TeamMode.entries.map { it.wireValue }) // fixed by the protocol
        assertNull(TeamMode.fromWire(6))
        assertEquals(TeamMode.TWO_TEAMS, TeamMode.fromWireOrDefault(99))
    }

    @Test
    fun threeTeams_dealsTheNumbersOutInThree() {
        val three = TeamLayout.defaultFor(TeamMode.THREE_TEAMS)
        assertArrayEquals(intArrayOf(22, 21, 21), three.teamSizes())
        assertEquals(1, three.teamOf(22))
        assertEquals(2, three.teamOf(23))
        assertEquals(3, three.teamOf(64))
        // Sizes for three teams that add up are kept; others fall back
        assertArrayEquals(intArrayOf(30, 20, 14), three.withSizes(intArrayOf(30, 20, 14)).teamSizes())
        assertArrayEquals(intArrayOf(22, 21, 21), three.withSizes(intArrayOf(32, 32)).teamSizes())
    }

    @Test
    fun squads_playAsTeams_andEveryoneElseAlone() {
        val squads = TeamLayout.defaultFor(TeamMode.SQUADS).withSquads(mapOf(1 to setOf(3, 7), 5 to setOf(10)))
        assertEquals(TeamLayout.SQUAD_TEAM_BASE + 1, squads.teamOf(3))
        assertEquals(squads.teamOf(3), squads.teamOf(7))
        assertEquals(TeamLayout.SQUAD_TEAM_BASE + 5, squads.teamOf(10))
        // Alone: their own number, as in free for all, never a squad's
        assertEquals(4, squads.teamOf(4))
        assertEquals(1, squads.squadOf(3))
        assertNull(squads.squadOf(4))
        // Each squad its own colour; nobody alone has one
        assertEquals(TeamColors.TEAM_1, squads.colorOf(squads.teamOf(3)))
        assertEquals(TeamColors.NONE, squads.colorOf(4))
    }

    @Test
    fun squads_areCleanedUp_andOnlyInSquadMode() {
        val squads = TeamLayout.defaultFor(TeamMode.SQUADS)
            .withSquads(mapOf(1 to setOf(3, 99), 2 to setOf(3, 4), 3 to emptySet(), 70 to setOf(5)))
        // A player in one squad at most, only real players and squads, none empty
        assertEquals(mapOf(1 to setOf(3), 2 to setOf(4)), squads.squads)
        assertEquals(emptyMap<Int, Set<Int>>(), TeamLayout.DEFAULT.withSquads(mapOf(1 to setOf(3))).squads)
        // Another mode starts with none
        assertEquals(emptyMap<Int, Set<Int>>(), squads.withMode(TeamMode.FREE_FOR_ALL).withMode(TeamMode.SQUADS).squads)
    }

    @Test
    fun sixtyFourSquadColours_allDifferent() {
        val colours = (1..64).map { TeamColors.squadColor(it) }
        assertEquals(64, colours.toSet().size)
        assertTrue(colours.none { it == TeamColors.NONE })
        assertEquals(TeamColors.squadColor(9), TeamColors.colorOf(TeamLayout.SQUAD_TEAM_BASE + 9))
    }
}
