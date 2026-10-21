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

import com.simplecoil.simplecoil.domain.map.Border
import com.simplecoil.simplecoil.domain.map.FieldConfig
import com.simplecoil.simplecoil.domain.map.GeoMath
import com.simplecoil.simplecoil.domain.map.LatLon
import com.simplecoil.simplecoil.domain.map.Spawn
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class FlagAndExtractionTest {

    private val center = LatLon(39.0, -77.0)
    private fun at(east: Double) = GeoMath.offset(center, 0.0, east)
    private val redBase = at(-80.0)
    private val blueBase = at(80.0)
    private val rules = FlagRules(capturesToWin = 2, droppedReturnsSeconds = 30, baseRadiusMeters = 10.0)

    private fun engine() = FlagEngine(rules, mapOf(1 to redBase, 2 to blueBase), 0)

    @Test
    fun takeTheFlagHomeToScore() {
        val e = engine()
        // Red player 1 walks into blue's base and takes the flag
        var state = e.tick(1_000, mapOf(1 to PlayerInfo(1, blueBase, true)))
        assertEquals(1, state.flags.first { it.team == 2 }.carrier)
        // Halfway home it travels with them
        state = e.tick(2_000, mapOf(1 to PlayerInfo(1, center, true)))
        assertEquals(center, state.flags.first { it.team == 2 }.point)
        // Home: a capture, and the flag goes back
        state = e.tick(3_000, mapOf(1 to PlayerInfo(1, redBase, true)))
        assertEquals(1, state.scores[1])
        assertTrue(state.flags.first { it.team == 2 }.home)
        assertEquals(0, state.winner)
    }

    @Test
    fun noScoreWhileYourOwnFlagIsAway() {
        val e = engine()
        e.tick(1_000, mapOf(1 to PlayerInfo(1, blueBase, true), 2 to PlayerInfo(2, redBase, true)))
        // Both flags taken: red can't score until theirs is back
        val state = e.tick(2_000, mapOf(1 to PlayerInfo(1, redBase, true), 2 to PlayerInfo(2, center, true)))
        assertEquals(0, state.scores[1])
    }

    @Test
    fun aDroppedFlagGoesHomeByTouchOrTime() {
        val e = engine()
        e.tick(1_000, mapOf(1 to PlayerInfo(1, blueBase, true)))
        e.tick(2_000, mapOf(1 to PlayerInfo(1, center, true)))
        e.onEliminated(1, 2_500)
        var state = e.tick(3_000, mapOf(1 to PlayerInfo(1, center, false)))
        val dropped = state.flags.first { it.team == 2 }
        assertTrue(dropped.dropped)
        assertEquals(center, dropped.point)
        // A blue player touches it: home
        state = e.tick(4_000, mapOf(2 to PlayerInfo(2, center, true)))
        assertTrue(state.flags.first { it.team == 2 }.home)

        // Dropped again, and left: home after the timeout
        e.tick(5_000, mapOf(1 to PlayerInfo(1, blueBase, true)))
        e.onEliminated(1, 6_000)
        state = e.tick(20_000, emptyMap())
        assertFalse(state.flags.first { it.team == 2 }.home)
        state = e.tick(40_000, emptyMap())
        assertTrue(state.flags.first { it.team == 2 }.home)
    }

    @Test
    fun theCarrierShowsEveryFewSeconds() {
        val e = FlagEngine(rules.copy(carrierShownEverySeconds = 10), mapOf(1 to redBase, 2 to blueBase), 0)
        assertTrue(e.tick(1_000, emptyMap()).flags.all { it.revealed })
        assertFalse(e.tick(5_000, emptyMap()).flags.any { it.revealed })
        assertTrue(e.tick(11_000, emptyMap()).flags.all { it.revealed })
    }

    private fun oneFlag() = FlagEngine(rules.copy(oneFlag = true), mapOf(1 to redBase, 2 to blueBase), 0, center)

    @Test
    fun oneFlag_eitherTeamTakesItHomeToScore() {
        val e = oneFlag()
        var state = e.tick(0, emptyMap())
        assertEquals(listOf(FlagEngine.NEUTRAL), state.flags.map { it.team })
        assertEquals(mapOf(1 to redBase, 2 to blueBase), state.flagBases)
        // Blue grabs it in the middle and runs it to blue's base
        state = e.tick(1_000, mapOf(5 to PlayerInfo(2, center, true)))
        assertEquals(5, state.flags.single().carrier)
        assertEquals(2, state.flags.single().carrierTeam)
        state = e.tick(2_000, mapOf(5 to PlayerInfo(2, blueBase, true)))
        assertEquals(1, state.scores[2])
        assertEquals(0, state.scores[1])
        // Back in the middle
        assertTrue(state.flags.single().home)
        assertEquals(center, state.flags.single().point)
        // The wire carries it all
        assertEquals(state, ModeState.fromJson(state.toJson()))
    }

    @Test
    fun oneFlag_bothTeamsOnItMeansNobodyGetsIt() {
        val e = oneFlag()
        var state = e.tick(1_000, mapOf(1 to PlayerInfo(1, center, true), 5 to PlayerInfo(2, at(3.0), true)))
        assertEquals(0, state.flags.single().carrier)
        // Still nobody, however long they stand there
        state = e.tick(9_000, mapOf(1 to PlayerInfo(1, center, true), 5 to PlayerInfo(2, at(3.0), true)))
        assertEquals(0, state.flags.single().carrier)
        // Blue steps off: red, still on it, takes it
        state = e.tick(10_000, mapOf(1 to PlayerInfo(1, center, true), 5 to PlayerInfo(2, at(40.0), true)))
        assertEquals(1, state.flags.single().carrier)
        // An eliminated player on it doesn't count
        val e2 = oneFlag()
        state = e2.tick(1_000, mapOf(1 to PlayerInfo(1, center, true), 5 to PlayerInfo(2, center, false)))
        assertEquals(1, state.flags.single().carrier)
    }

    @Test
    fun oneFlag_sameTeam_theFirstThereTakesIt() {
        val e = oneFlag()
        // Player 9 arrives first, while an enemy is also on it, so nobody takes it yet
        e.tick(1_000, mapOf(9 to PlayerInfo(1, center, true), 5 to PlayerInfo(2, center, true)))
        // Teammate 3 arrives after
        e.tick(2_000, mapOf(9 to PlayerInfo(1, center, true), 3 to PlayerInfo(1, at(2.0), true), 5 to PlayerInfo(2, center, true)))
        // The enemy leaves: 9 was there first, though 3 has the lower id
        val state = e.tick(3_000, mapOf(9 to PlayerInfo(1, center, true), 3 to PlayerInfo(1, at(2.0), true)))
        assertEquals(9, state.flags.single().carrier)
    }

    @Test
    fun oneFlag_droppedAnyoneGrabsIt_orItGoesBackToTheMiddle() {
        val e = oneFlag()
        e.tick(1_000, mapOf(1 to PlayerInfo(1, center, true)))
        e.tick(2_000, mapOf(1 to PlayerInfo(1, at(-40.0), true)))
        e.onEliminated(1, 2_500)
        var state = e.tick(3_000, emptyMap())
        assertTrue(state.flags.single().dropped)
        assertEquals(at(-40.0), state.flags.single().point)
        // Blue picks it up where it fell
        state = e.tick(4_000, mapOf(5 to PlayerInfo(2, at(-40.0), true)))
        assertEquals(5, state.flags.single().carrier)
        // Dropped and left: back to the middle after the timeout
        e.onEliminated(5, 5_000)
        state = e.tick(40_000, emptyMap())
        assertTrue(state.flags.single().home)
        assertEquals(center, state.flags.single().point)
    }

    @Test
    fun theOneFlagIsPlacedFairlyInsideTheField() {
        val field = Border.Circle(center, 100.0)
        val spot = centerFlagSpot(field, mapOf(1 to redBase, 2 to blueBase), 10.0, 10.0)!!
        assertTrue(field.distanceToEdgeMeters(spot) >= 20.0)
        val unfair = GeoMath.distanceMeters(spot, redBase) - GeoMath.distanceMeters(spot, blueBase)
        assertTrue("off by $unfair m", kotlin.math.abs(unfair) < 6.0)
        assertTrue(GeoMath.distanceMeters(spot, center) < 10.0)
        // An L-shaped field whose middle is outside it: still inside, and fair
        val l = Border.Polygon(listOf(GeoMath.offset(center, -100.0, -100.0), GeoMath.offset(center, -100.0, 100.0), GeoMath.offset(center, -40.0, 100.0),
            GeoMath.offset(center, -40.0, -40.0), GeoMath.offset(center, 100.0, -40.0), GeoMath.offset(center, 100.0, -100.0)))
        val a = GeoMath.offset(center, -70.0, 80.0)
        val b = GeoMath.offset(center, 80.0, -70.0)
        val lSpot = centerFlagSpot(l, mapOf(1 to a, 2 to b), 10.0, 5.0)!!
        assertTrue(l.contains(lSpot))
        assertTrue(kotlin.math.abs(GeoMath.distanceMeters(lSpot, a) - GeoMath.distanceMeters(lSpot, b)) < 10.0)
        // The rules and the hand-placed flag survive saving
        val config = FieldConfig(border = field, flag = FlagRules(oneFlag = true), centerFlag = at(5.0))
        val back = FieldConfig.fromJson(JSONObject(config.toJson().toString()))
        assertTrue(back!!.flag.oneFlag)
        assertEquals(at(5.0), back.centerFlag)
    }

    @Test
    fun flagBasesAreTheSpawnsOrPlacedApart() {
        val field = Border.Circle(center, 100.0)
        assertEquals(mapOf(1 to redBase, 2 to blueBase), flagBases(field, listOf(Spawn(1, redBase), Spawn(2, blueBase)), 10.0))
        val placed = flagBases(field, emptyList(), 10.0, Random(1))
        assertEquals(setOf(1, 2), placed.keys)
        assertTrue(GeoMath.distanceMeters(placed.getValue(1), placed.getValue(2)) > 50.0)
    }

    @Test
    fun lootIsCollectedThenBanked() {
        val field = Border.Circle(center, 100.0)
        val rules = ExtractionRules(lootPoints = 3, collectSeconds = 5, opensAfterMinutes = 1, zones = 1, radiusMeters = 8.0)
        val e = ExtractionEngine(rules, field, emptyList(), 0, Random(2))
        var state = e.tick(0, emptyMap())
        val ex = state.extraction!!
        assertEquals(3, ex.loot.size)
        assertEquals(1, ex.zones.size)
        val pile = ex.loot.first()
        // Standing on a pile for the collect time picks it up, and a new pile appears
        e.tick(1_000, mapOf(7 to PlayerInfo(1, pile.point, true)))
        state = e.tick(6_000, mapOf(7 to PlayerInfo(1, pile.point, true)))
        assertEquals(pile.value, state.extraction!!.carrying[7])
        assertEquals(3, state.extraction!!.loot.size)

        // The zone before it opens does nothing; after, standing there banks it
        val zone = state.extraction!!.zones.single()
        e.tick(10_000, mapOf(7 to PlayerInfo(1, zone, true)))
        state = e.tick(20_000, mapOf(7 to PlayerInfo(1, zone, true)))
        assertEquals(0, state.scores[1])
        e.tick(61_000, mapOf(7 to PlayerInfo(1, zone, true)))
        state = e.tick(67_000, mapOf(7 to PlayerInfo(1, zone, true)))
        assertEquals(pile.value, state.scores[1])
        assertEquals(null, state.extraction!!.carrying[7])
    }

    @Test
    fun onASmallField_theLootStillSpreadsOut() {
        // A house lot: about 30 by 60 m, two spawns at the ends, too small to keep everything clear
        val lot = Border.Polygon(listOf(GeoMath.offset(center, -30.0, -15.0), GeoMath.offset(center, -30.0, 15.0),
            GeoMath.offset(center, 30.0, 15.0), GeoMath.offset(center, 30.0, -15.0)))
        val avoid = listOf(GeoMath.offset(center, -25.0, 0.0) to 30.0, GeoMath.offset(center, 25.0, 0.0) to 30.0)
        repeat(5) { seed ->
            val loot = ExtractionEngine(ExtractionRules(lootPoints = 6, zones = 1, radiusMeters = 5.0), lot, avoid, 0, Random(seed))
                .tick(0, emptyMap()).extraction!!.loot
            assertEquals(6, loot.size)
            for (a in loot) for (b in loot) if (a.id < b.id) {
                assertTrue("piles ${a.id} and ${b.id} share a spot", GeoMath.distanceMeters(a.point, b.point) > 5.0)
            }
        }
    }

    @Test
    fun anEliminatedCarrierDropsTheirLoot() {
        val field = Border.Circle(center, 100.0)
        val e = ExtractionEngine(ExtractionRules(lootPoints = 3, collectSeconds = 3), field, emptyList(), 0, Random(3))
        val pile = e.tick(0, emptyMap()).extraction!!.loot.first()
        e.tick(1_000, mapOf(7 to PlayerInfo(1, pile.point, true)))
        e.tick(5_000, mapOf(7 to PlayerInfo(1, pile.point, true)))
        e.onEliminated(7)
        val state = e.tick(6_000, emptyMap())
        assertEquals(4, state.extraction!!.loot.size)
        assertTrue(state.extraction!!.loot.any { it.point == pile.point && it.value == pile.value })
    }

    @Test
    fun theStateAndRulesGoOverTheWire() {
        val flagState = engine().tick(1_000, mapOf(1 to PlayerInfo(1, blueBase, true)))
        assertEquals(flagState, ModeState.fromJson(JSONObject(flagState.toJson().toString())))
        val exState = ExtractionEngine(ExtractionRules(), Border.Circle(center, 100.0), emptyList(), 0, Random(4)).tick(0, emptyMap())
        assertEquals(exState, ModeState.fromJson(JSONObject(exState.toJson().toString())))
        val config = FieldConfig(mode = GameMode.EXTRACTION, flag = rules, extraction = ExtractionRules(lootPoints = 9))
        val back = FieldConfig.fromJson(config.toJson())
        assertEquals(rules, back.flag)
        assertEquals(9, back.extraction.lootPoints)
    }
}
