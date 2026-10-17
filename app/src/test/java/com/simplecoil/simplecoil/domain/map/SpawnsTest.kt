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

package com.simplecoil.simplecoil.domain.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SpawnsTest {

    private val center = LatLon(39.0, -77.0)
    private fun at(northMeters: Double, eastMeters: Double) = GeoMath.offset(center, northMeters, eastMeters)

    /** A 200 m by 100 m field, long east to west. */
    private val field = Border.Polygon(listOf(at(-50.0, -100.0), at(-50.0, 100.0), at(50.0, 100.0), at(50.0, -100.0)))
    private val rules = SpawnRules()

    @Test
    fun twoTeamsGetOppositeEnds() {
        val result = SpawnPlanner.place(field, listOf(1, 2), rules, 10.0, random = Random(1))
        assertFalse(result.relaxed)
        val red = result.spawns.single { it.team == 1 }
        val blue = result.spawns.single { it.team == 2 }
        // Far apart: at least half the field's width
        assertTrue(GeoMath.distanceMeters(red.point, blue.point) >= 100.0)
        // At opposite ends, east and west
        val (redEast, _) = GeoMath.toLocal(center, red.point)
        val (blueEast, _) = GeoMath.toLocal(center, blue.point)
        assertTrue("red $redEast, blue $blueEast", redEast * blueEast < 0)
    }

    @Test
    fun spawnsStayInsideAndClearOfTheEdge() {
        val result = SpawnPlanner.place(field, listOf(1, 2, 3, 4), rules, 10.0, random = Random(2))
        assertEquals(4, result.spawns.size)
        for (spawn in result.spawns) {
            assertTrue(field.contains(spawn.point))
            if (!result.relaxed) assertTrue(field.distanceToEdgeMeters(spawn.point) >= 10.0 + rules.radiusMeters)
        }
        assertEquals(listOf(1, 2, 3, 4), result.spawns.map { it.team }.sorted())
    }

    @Test
    fun severalPerTeamSpreadOut() {
        val result = SpawnPlanner.place(field, listOf(1, 2), rules.copy(perTeam = 2), 5.0, random = Random(3))
        assertEquals(4, result.spawns.size)
        val reds = result.spawns.filter { it.team == 1 }
        assertEquals(2, reds.size)
        assertTrue(GeoMath.distanceMeters(reds[0].point, reds[1].point) >= rules.radiusMeters * 2)
    }

    @Test
    fun aTinyFieldLoosensTheRulesInsteadOfFailing() {
        val tiny = Border.Circle(center, 25.0)
        val result = SpawnPlanner.place(tiny, listOf(1, 2), rules.copy(radiusMeters = 8.0), 10.0, random = Random(4))
        assertTrue(result.relaxed)
        assertEquals(2, result.spawns.size)
        result.spawns.forEach { assertTrue(tiny.contains(it.point)) }
    }

    @Test
    fun freeForAllSpawnsAreEveryones() {
        val result = SpawnPlanner.place(field, SpawnPlanner.teamsFor(0), rules.copy(perTeam = 3), 10.0, random = Random(5))
        assertEquals(3, result.spawns.size)
        assertTrue(result.spawns.all { it.team == Spawn.ANYONE })
        assertTrue(result.spawns.all { it.isFor(7, freeForAll = true) })
    }

    @Test
    fun theSameSeedGivesTheSameSpawns() {
        val a = SpawnPlanner.place(field, listOf(1, 2), rules, 10.0, random = Random(9))
        val b = SpawnPlanner.place(field, listOf(1, 2), rules, 10.0, random = Random(9))
        assertEquals(a, b)
    }

    @Test
    fun movedSpawnsLandElsewhereAndAvoidEnemies() {
        val base = FieldConfig(border = field, rules = BorderRules(edgeBufferMeters = 5.0), spawnRules = rules)
        val start = SpawnPlanner.place(field, listOf(1, 2), rules, 5.0, random = Random(6)).spawns
        val blueNear = at(0.0, 0.0)
        val moved = SpawnPlanner.moved(base, start, 1, mapOf(2 to listOf(blueNear)), Random(7))!!
        assertEquals(1, moved.spawnGeneration)
        assertEquals(listOf(1, 2), moved.spawns.map { it.team }.sorted())
        for (spawn in moved.spawns) {
            val before = start.single { it.team == spawn.team }
            assertTrue(GeoMath.distanceMeters(before.point, spawn.point) >= rules.radiusMeters * 2)
        }
        // Red's new spawn isn't next to a blue player
        assertTrue(GeoMath.distanceMeters(moved.spawns.single { it.team == 1 }.point, blueNear) >= rules.enemyClearMeters)
        // The host's other settings come along unchanged
        assertEquals(base.rules, moved.rules)
    }

    @Test
    fun nothingToMove() {
        assertNull(SpawnPlanner.moved(FieldConfig(border = field), emptyList(), 1, emptyMap()))
        assertNull(SpawnPlanner.moved(FieldConfig(), listOf(Spawn(1, center)), 1, emptyMap()))
    }

    @Test
    fun whoUsesASpawn() {
        assertTrue(Spawn(1, center).isFor(1, freeForAll = false))
        assertFalse(Spawn(1, center).isFor(2, freeForAll = false))
        assertTrue(Spawn(Spawn.ANYONE, center).isFor(2, freeForAll = false))
        assertTrue(Spawn(1, center).isFor(9, freeForAll = true))
    }

    @Test
    fun spawnsAndTheirRulesGoToPhonesAndBack() {
        val config = FieldConfig("Park", field, BorderRules(), listOf(Spawn(1, at(0.0, -80.0)), Spawn(2, at(0.0, 80.0))),
            SpawnRules(walkBack = false, startAtSpawn = true, secondsInSpawn = 5, radiusMeters = 12.0, perTeam = 2,
                enemySpawnShare = 0.4, enemyClearMeters = 25.0, moveMinutes = 3, crossfadeSeconds = 8, oldSpawnGraceSeconds = 20,
                shieldOnlyInSpawn = true), spawnGeneration = 4)
        assertEquals(config, FieldConfig.fromBytes(config.toBytes()))
    }

    @Test
    fun aConfigFromBeforeSpawnsStillReads() {
        val old = org.json.JSONObject().put("name", "x")
        val config = FieldConfig.fromJson(old)
        assertTrue(config.spawns.isEmpty())
        assertEquals(SpawnRules(), config.spawnRules)
    }

    @Test
    fun spawnRulesAreClamped() {
        val wild = SpawnRules(secondsInSpawn = 99, radiusMeters = 1.0, perTeam = 9, enemySpawnShare = 2.0,
            enemyClearMeters = 500.0, moveMinutes = 60, crossfadeSeconds = -1, oldSpawnGraceSeconds = 999).clamped()
        assertEquals(30, wild.secondsInSpawn)
        assertEquals(5.0, wild.radiusMeters, 1e-9)
        assertEquals(4, wild.perTeam)
        assertEquals(0.9, wild.enemySpawnShare, 1e-9)
        assertEquals(50.0, wild.enemyClearMeters, 1e-9)
        assertEquals(15, wild.moveMinutes)
        assertEquals(0, wild.crossfadeSeconds)
        assertEquals(60, wild.oldSpawnGraceSeconds)
    }
}
