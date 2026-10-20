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
import com.simplecoil.simplecoil.domain.map.SpawnPlanner
import com.simplecoil.simplecoil.domain.map.SpawnRules
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ModesTest {

    private val center = LatLon(39.0, -77.0)
    private fun at(north: Double, east: Double) = GeoMath.offset(center, north, east)
    private val a = Objective("A", at(0.0, -50.0))
    private val b = Objective("B", at(0.0, 50.0))
    private val rules = ObjectiveRules(captureSeconds = 10, tickSeconds = 5, pointsPerTick = 1, scoreToWin = 50, moreCaptureFaster = true)

    private fun on(vararg teams: Pair<Int, Int>) = teams.toMap()

    @Test
    fun aTeamAloneTakesAPointThenScores() {
        val engine = ModeEngine(GameMode.DOMINATION, listOf(a, b), rules, 0)
        var state = engine.tick(5_000, mapOf("A" to on(1 to 1)))
        assertEquals(1, state.objectives[0].capturing)
        assertEquals(0.5, state.objectives[0].progress, 0.01)
        state = engine.tick(10_000, mapOf("A" to on(1 to 1)))
        assertEquals(1, state.objectives[0].owner)
        // Held: it keeps scoring with nobody on it
        state = engine.tick(15_000, emptyMap())
        assertEquals(2, state.scores[1])
        assertEquals(0, state.objectives[1].owner)
    }

    @Test
    fun morePlayersTakeItFaster() {
        val engine = ModeEngine(GameMode.DOMINATION, listOf(a), rules, 0)
        val state = engine.tick(5_000, mapOf("A" to on(1 to 3)))
        assertEquals(1, state.objectives[0].owner)
    }

    @Test
    fun twoTeamsOnAPointIsContested() {
        val engine = ModeEngine(GameMode.DOMINATION, listOf(a), rules, 0)
        engine.tick(4_000, mapOf("A" to on(1 to 1)))
        val state = engine.tick(8_000, mapOf("A" to on(1 to 1, 2 to 1)))
        assertTrue(state.objectives[0].contested)
        assertEquals(0.4, state.objectives[0].progress, 0.01)
    }

    @Test
    fun progressDrainsWhenTheyLeave() {
        val engine = ModeEngine(GameMode.DOMINATION, listOf(a), rules, 0)
        engine.tick(6_000, mapOf("A" to on(1 to 1)))
        var state = engine.tick(9_000, emptyMap())
        assertEquals(0.3, state.objectives[0].progress, 0.01)
        state = engine.tick(20_000, emptyMap())
        assertEquals(0, state.objectives[0].capturing)
    }

    @Test
    fun theEnemyCanTakeAHeldPoint() {
        val engine = ModeEngine(GameMode.DOMINATION, listOf(a), rules, 0)
        engine.tick(10_000, mapOf("A" to on(1 to 1)))
        val state = engine.tick(20_000, mapOf("A" to on(2 to 1)))
        assertEquals(2, state.objectives[0].owner)
    }

    @Test
    fun firstToTheScoreWins() {
        val engine = ModeEngine(GameMode.DOMINATION, listOf(a, b), rules.copy(scoreToWin = 50), 0)
        engine.tick(10_000, mapOf("A" to on(1 to 1), "B" to on(1 to 1)))
        // Two points, one each every 5 s: 50 by 125 s
        val state = engine.tick(130_000, emptyMap())
        assertEquals(1, state.winner)
    }

    @Test
    fun hardpointMovesAndOnlyTheActivePointCounts() {
        val c = Objective("C", at(50.0, 0.0))
        val engine = ModeEngine(GameMode.HARDPOINT, listOf(a, b, c), rules.copy(moveSeconds = 60, showNextSeconds = 20), 0)
        var state = engine.tick(5_000, mapOf("A" to on(1 to 1), "B" to on(2 to 1)))
        assertEquals(1, state.objectives[0].owner)
        assertEquals(0, state.objectives[1].owner)
        assertEquals(1, state.scores[1])
        assertFalse(state.objectives[1].next)

        state = engine.tick(45_000, mapOf("A" to on(1 to 1)))
        assertTrue(state.objectives[1].next)
        state = engine.tick(60_000, mapOf("A" to on(1 to 1), "B" to on(2 to 1)))
        assertTrue(state.objectives[1].active)
        assertFalse(state.objectives[0].active)
        assertEquals(2, state.objectives[1].owner)
        assertEquals(120_000L, state.movesAt)
    }

    @Test
    fun theStateGoesOverTheWire() {
        val engine = ModeEngine(GameMode.DOMINATION, listOf(a, b), rules, 0)
        val state = engine.tick(5_000, mapOf("A" to on(2 to 1)))
        assertEquals(state, ModeState.fromJson(JSONObject(state.toJson().toString())))
    }

    @Test
    fun theFieldCarriesItsModeAndPoints() {
        val config = FieldConfig(mode = GameMode.HARDPOINT, objectives = listOf(a, b), objectiveRules = rules)
        val back = FieldConfig.fromJson(config.toJson())
        assertEquals(GameMode.HARDPOINT, back.mode)
        assertEquals(listOf(a, b), back.objectives)
        assertEquals(rules, back.objectiveRules)
        assertEquals(GameMode.CLASSIC, FieldConfig.fromJson(JSONObject()).mode)
        // Each mode's settings come back, so switching modes and back keeps them
        val both = config.copy(modeRules = mapOf(GameMode.DOMINATION to rules, GameMode.HARDPOINT to ObjectiveRules.defaultsFor(GameMode.HARDPOINT)))
        assertEquals(both.modeRules, FieldConfig.fromJson(both.toJson()).modeRules)
        assertEquals(ObjectiveRules.MIN_RADIUS_METERS, ObjectiveRules(radiusMeters = 0.5).clamped().radiusMeters, 0.0)
    }

    @Test
    fun eachModeGetsTheTeamModeItNeeds() {
        val ffa = com.simplecoil.simplecoil.domain.team.TeamMode.FREE_FOR_ALL
        val two = com.simplecoil.simplecoil.domain.team.TeamMode.TWO_TEAMS
        val four = com.simplecoil.simplecoil.domain.team.TeamMode.FOUR_TEAMS
        // Infection and Battle Royale are free for all; CTF and S&D two teams, whatever was set
        assertEquals(ffa, GameMode.INFECTION.teamModeFor(null, two))
        assertEquals(ffa, GameMode.BATTLE_ROYALE.teamModeFor(four, two))
        assertEquals(setOf(ffa), GameMode.BATTLE_ROYALE.teamModes)
        assertEquals(two, GameMode.CAPTURE_THE_FLAG.teamModeFor(null, ffa))
        assertEquals(two, GameMode.SEARCH_AND_DESTROY.teamModeFor(four, four))
        // The team mode it was last played with comes back
        assertEquals(four, GameMode.DOMINATION.teamModeFor(four, ffa))
        // Domination and Hardpoint are team games: free for all becomes two teams; four teams stays
        assertEquals(two, GameMode.DOMINATION.teamModeFor(null, ffa))
        assertEquals(setOf(two, four), GameMode.HARDPOINT.teamModes)
        // Never played: a mode that takes any keeps what is set
        assertEquals(ffa, GameMode.CLASSIC.teamModeFor(null, ffa))
    }

    // Placement

    @Test
    fun theFieldsSizePicksHowManyPoints() {
        val rules = ObjectiveRules()
        assertEquals(1, ObjectivePlanner.count(GameMode.DOMINATION, Border.Circle(center, 25.0), rules))
        assertEquals(2, ObjectivePlanner.count(GameMode.DOMINATION, Border.Circle(center, 40.0), rules))
        assertEquals(3, ObjectivePlanner.count(GameMode.DOMINATION, Border.Circle(center, 100.0), rules))
        assertEquals(5, ObjectivePlanner.count(GameMode.DOMINATION, Border.Circle(center, 100.0), rules.copy(pointCount = 5)))
        assertEquals(5, ObjectivePlanner.count(GameMode.HARDPOINT, Border.Circle(center, 100.0), rules))
        // Hardpoint always moves between spots, whatever the count says
        assertEquals(3, ObjectivePlanner.count(GameMode.HARDPOINT, Border.Circle(center, 25.0), rules.copy(pointCount = 1)))
        val square = Border.Polygon(listOf(at(0.0, 0.0), at(0.0, 100.0), at(100.0, 100.0), at(100.0, 0.0)))
        assertEquals(10_000.0, ObjectivePlanner.areaSquareMeters(square), 50.0)
    }

    @Test
    fun pointsStayApartAndClearOfSpawns() {
        val border = Border.Circle(center, 120.0)
        val spawns = listOf(Spawn(1, at(0.0, -90.0)), Spawn(2, at(0.0, 90.0)))
        val rules = ObjectiveRules(radiusMeters = 10.0)
        val points = ObjectivePlanner.place(border, 3, rules, spawns, 15.0, 10.0, Random(1))
        assertEquals(listOf("A", "B", "C"), points.map { it.id })
        for (p in points) {
            assertTrue(border.contains(p.point))
            spawns.forEach { s -> assertTrue(GeoMath.distanceMeters(p.point, s.point) >= 15.0 + 10.0 + ObjectivePlanner.GAP_METERS) }
            points.filter { it !== p }.forEach { q -> assertTrue(GeoMath.distanceMeters(p.point, q.point) >= 2 * 10.0 + ObjectivePlanner.GAP_METERS) }
        }
        // Lettered from team 1's side to team 2's
        val (aEast, _) = GeoMath.toLocal(center, points[0].point)
        val (cEast, _) = GeoMath.toLocal(center, points[2].point)
        assertTrue(aEast < cEast)
    }

    @Test
    fun spawnsKeepClearOfPoints() {
        val border = Border.Circle(center, 120.0)
        val point = Objective("A", center)
        val config = FieldConfig(border = border, objectives = listOf(point))
        val result = SpawnPlanner.place(border, listOf(1, 2), SpawnRules(), 10.0, random = Random(2), keepClear = SpawnPlanner.objectiveClearance(config))
        result.spawns.forEach { assertTrue(GeoMath.distanceMeters(it.point, center) >= 10.0 + ObjectivePlanner.GAP_METERS + SpawnRules().radiusMeters) }
    }
}
