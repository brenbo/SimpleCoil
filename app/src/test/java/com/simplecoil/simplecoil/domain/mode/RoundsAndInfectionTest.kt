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

import com.simplecoil.simplecoil.domain.map.FieldConfig
import com.simplecoil.simplecoil.domain.map.GeoMath
import com.simplecoil.simplecoil.domain.map.LatLon
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class RoundsAndInfectionTest {

    private val center = LatLon(39.0, -77.0)
    private fun at(east: Double) = GeoMath.offset(center, 0.0, east)
    private val siteA = Objective("A", at(-40.0))
    private val siteB = Objective("B", at(40.0))
    private val rules = BombRules(roundsToWin = 3, roundSeconds = 120, plantSeconds = 5, defuseSeconds = 8, fuseSeconds = 30,
        betweenRoundsSeconds = 10, siteRadiusMeters = 8.0)

    /** Attacker 1 (team 1) and defender 2 (team 2), both somewhere quiet. */
    private fun players(attacker: LatLon = center, defender: LatLon = at(100.0), attackerAlive: Boolean = true) =
        mapOf(1 to PlayerInfo(1, attacker, attackerAlive), 2 to PlayerInfo(2, defender, true))

    @Test
    fun plantingThenTheFuseWinsTheRound() {
        val e = BombEngine(rules, listOf(siteA, siteB), 0, Random(1))
        var state = e.tick(1_000, players())
        assertEquals(1, state.bomb!!.carrier)
        e.tick(2_000, players(attacker = siteA.point))
        state = e.tick(7_500, players(attacker = siteA.point))
        assertEquals(RoundPhase.PLANTED, state.bomb!!.phase)
        assertEquals("A", state.bomb!!.plantedAt)
        state = e.tick(40_000, players())
        assertEquals(1, state.scores[1])
        assertEquals(RoundPhase.BETWEEN, state.bomb!!.phase)
        // The next round, and everyone back
        state = e.tick(50_500, players())
        assertEquals(2, state.round)
        assertEquals(RoundPhase.LIVE, state.bomb!!.phase)
        assertTrue(state.noRespawn)
    }

    @Test
    fun aDefuseWinsItForTheDefenders() {
        val e = BombEngine(rules, listOf(siteA), 0, Random(2))
        e.tick(1_000, players())
        e.tick(2_000, players(attacker = siteA.point))
        e.tick(8_000, players(attacker = siteA.point))
        e.tick(9_000, players(defender = siteA.point))
        val state = e.tick(18_000, players(defender = siteA.point))
        assertEquals(1, state.scores[2])
    }

    @Test
    fun theClockRunningOutWinsItForTheDefenders() {
        val e = BombEngine(rules, listOf(siteA), 0, Random(3))
        e.tick(1_000, players())
        val state = e.tick(121_000, players())
        assertEquals(1, state.scores[2])
    }

    @Test
    fun anEliminatedCarrierDropsTheBombAndTheLastSideStandingWins() {
        val e = BombEngine(rules, listOf(siteA), 0, Random(4))
        val three = players() + (3 to PlayerInfo(1, at(-20.0), true))
        val carrier = e.tick(1_000, three).bomb!!.carrier
        e.onEliminated(carrier)
        var state = e.tick(2_000, three.mapValues { (id, p) -> if (id == carrier) p.copy(alive = false) else p })
        assertNotNull(state.bomb!!.bomb)
        assertEquals(0, state.bomb!!.carrier)
        // The other attacker goes out too: the defenders win
        val other = if (carrier == 1) 3 else 1
        e.onEliminated(other)
        state = e.tick(3_000, three)
        assertEquals(1, state.scores[2])
    }

    @Test
    fun playersStillInTheirCountdown_dontLoseTheRound() {
        val e = BombEngine(rules, listOf(siteA), 0, Random(6))
        // The start (and each new round's) countdown: nobody is in the game yet
        val counting = players(attackerAlive = false).mapValues { it.value.copy(alive = false) }
        var state = e.tick(1_000, counting)
        state = e.tick(5_000, counting)
        assertEquals(RoundPhase.LIVE, state.bomb!!.phase)
        assertEquals(0, state.scores[1])
        assertEquals(0, state.scores[2])
        // Only an elimination wipes a team out: one round, one point
        e.tick(11_000, players())
        e.onEliminated(1)
        state = e.tick(12_000, players(attackerAlive = false))
        assertEquals(1, state.scores[2])
        // The next round starts with the attacker counting down again: no round given away
        state = e.tick(22_500, players(attackerAlive = false))
        assertEquals(2, state.round)
        state = e.tick(30_000, players(attackerAlive = false))
        assertEquals(RoundPhase.LIVE, state.bomb!!.phase)
        assertEquals(1, state.scores[2])
    }

    @Test
    fun theTeamsSwapAtHalfTime() {
        val e = BombEngine(rules, listOf(siteA), 0, Random(5))
        assertEquals(1, e.attackers(1))
        assertEquals(1, e.attackers(2))
        assertEquals(2, e.attackers(3))
        assertEquals(1, BombEngine(rules.copy(swapSides = false), listOf(siteA), 0).attackers(5))
    }

    @Test
    fun theInfectedSpreadAndWin() {
        val e = InfectionEngine(InfectionRules(startingInfected = 1, revealEverySeconds = 30, roundMinutes = 5), 0, Random(6))
        val everyone = (1..3).associateWith { PlayerInfo(it, center, true) }
        var state = e.tick(1_000, everyone)
        assertEquals(1, state.infected.size)
        val zombie = state.infected.single()
        assertEquals(InfectionEngine.INFECTED, state.sideOf(zombie, zombie))
        // Survivors show to the infected early in each interval
        assertEquals(2, state.revealed.size)
        val survivors = everyone.keys - zombie
        // A survivor tagging a survivor doesn't spread it
        e.onEliminated(survivors.last(), survivors.first())
        e.onEliminated(survivors.first(), zombie)
        state = e.tick(10_000, everyone)
        assertEquals(mapOf(InfectionEngine.SURVIVORS to 1, InfectionEngine.INFECTED to 2), state.scores)
        assertTrue(state.revealed.isEmpty())
        e.onEliminated(survivors.last(), zombie)
        assertEquals(InfectionEngine.INFECTED, e.tick(11_000, everyone).winner)
    }

    @Test
    fun survivorsWhoLastWin() {
        val e = InfectionEngine(InfectionRules(roundMinutes = 3), 0, Random(7))
        val everyone = (1..3).associateWith { PlayerInfo(it, center, true) }
        e.tick(1_000, everyone)
        assertEquals(InfectionEngine.SURVIVORS, e.tick(181_000, everyone).winner)
    }

    @Test
    fun theStateAndRulesGoOverTheWire() {
        val bomb = BombEngine(rules, listOf(siteA, siteB), 0, Random(8)).tick(1_000, players())
        assertEquals(bomb, ModeState.fromJson(JSONObject(bomb.toJson().toString())))
        val infection = InfectionEngine(InfectionRules(), 0, Random(9)).tick(1_000, (1..3).associateWith { PlayerInfo(it, center, true) })
        assertEquals(infection, ModeState.fromJson(JSONObject(infection.toJson().toString())))
        val config = FieldConfig(mode = GameMode.SEARCH_AND_DESTROY, bomb = rules, infection = InfectionRules(startingInfected = 2))
        val back = FieldConfig.fromJson(config.toJson())
        assertEquals(rules, back.bomb)
        assertEquals(2, back.infection.startingInfected)
    }
}
