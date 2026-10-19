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

package com.simplecoil.simplecoil.domain.mode

import com.simplecoil.simplecoil.domain.mode.GameResult.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GameResultTest {

    private fun state(scores: Map<Int, Int>, winner: Int = 0) =
        ModeState(GameMode.DOMINATION, emptyList(), scores, 100, 10.0, winner, 0)

    /** Ids 1-8 on team 1, 9-16 on team 2. */
    private val teamOf = { id: Int -> if (id <= 8) 1 else 2 }

    @Test
    fun anObjectiveGameGoesByItsWinner() {
        assertEquals(GameResult(Outcome.VICTORY, 100, 40), GameResult.of(state(mapOf(1 to 100, 2 to 40), winner = 1), 3, 1, false, emptyMap(), teamOf))
        assertEquals(GameResult(Outcome.DEFEAT, 40, 100), GameResult.of(state(mapOf(1 to 100, 2 to 40), winner = 1), 9, 2, false, emptyMap(), teamOf))
    }

    @Test
    fun anObjectiveGameEndedOnTime_goesByTheScores() {
        assertEquals(Outcome.VICTORY, GameResult.of(state(mapOf(1 to 30, 2 to 20)), 3, 1, false, emptyMap(), teamOf)!!.outcome)
        assertEquals(Outcome.DRAW, GameResult.of(state(mapOf(1 to 20, 2 to 20)), 3, 1, false, emptyMap(), teamOf)!!.outcome)
    }

    @Test
    fun aClassicTeamGame_goesByTheTeamsTotals() {
        val points = mapOf(1 to 4, 2 to 3, 9 to 5, 10 to 1)
        assertEquals(GameResult(Outcome.VICTORY, 7, 6), GameResult.of(null, 1, 1, false, points, teamOf))
        assertEquals(GameResult(Outcome.DEFEAT, 6, 7), GameResult.of(null, 9, 2, false, points, teamOf))
        assertEquals(Outcome.DRAW, GameResult.of(null, 1, 1, false, mapOf(1 to 2, 9 to 2), teamOf)!!.outcome)
    }

    @Test
    fun classicFreeForAll_andOneSidedGames_showNothing() {
        assertNull(GameResult.of(null, 1, 1, true, mapOf(1 to 4, 2 to 3), teamOf))
        assertNull(GameResult.of(null, 1, 1, false, mapOf(1 to 4, 2 to 3), teamOf))
    }
}
