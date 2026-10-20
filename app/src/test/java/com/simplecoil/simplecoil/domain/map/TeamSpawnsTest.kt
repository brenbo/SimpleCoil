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

import com.simplecoil.simplecoil.domain.team.TeamMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class TeamSpawnsTest {

    private val center = LatLon(39.0, -77.0)
    private val field = FieldConfig(border = Border.Circle(center, 100.0))
    private fun spawn(team: Int, north: Double) = Spawn(team, GeoMath.offset(center, north, 0.0))
    private fun teams(config: FieldConfig) = config.spawns.map { it.team }.sorted()

    @Test
    fun fourTeams_withSpawnsForTwo_placesTheOtherTwo_andKeepsThePlacedOnes() {
        val two = field.copy(spawns = listOf(spawn(1, 60.0), spawn(2, -60.0)))
        val four = TeamSpawns.forMode(two, TeamMode.FOUR_TEAMS, Random(1))
        assertEquals(listOf(1, 2, 3, 4), teams(four))
        assertEquals(two.spawns, four.spawns.filter { it.team <= 2 })
        assertTrue(four.spawns.all { field.border!!.contains(it.point) })
    }

    @Test
    fun twoTeams_withSpawnsForFour_dropsTheExtraTeams() {
        val four = field.copy(spawns = (1..4).map { spawn(it, it * 10.0) })
        assertEquals(listOf(1, 2), teams(TeamSpawns.forMode(four, TeamMode.TWO_TEAMS)))
    }

    @Test
    fun freeForAll_everySpawnIsEveryones() {
        val four = field.copy(spawns = (1..4).map { spawn(it, it * 10.0) })
        assertEquals(listOf(0, 0, 0, 0), teams(TeamSpawns.forMode(four, TeamMode.FREE_FOR_ALL)))
        // Spawns already made for anyone are the ones used
        val mixed = four.copy(spawns = four.spawns + spawn(Spawn.ANYONE, 0.0))
        assertEquals(listOf(0), teams(TeamSpawns.forMode(mixed, TeamMode.FREE_FOR_ALL)))
    }

    @Test
    fun noSpawns_orNothingToChange_isTheSameField() {
        assertSame(field, TeamSpawns.forMode(field, TeamMode.FOUR_TEAMS))
        val two = field.copy(spawns = listOf(spawn(1, 60.0), spawn(2, -60.0)))
        assertSame(two, TeamSpawns.forMode(two, TeamMode.TWO_TEAMS))
    }

    @Test
    fun theCache_placesOnce_perFieldAndTeamMode() {
        val cache = TeamSpawnsCache(Random(2))
        val two = field.copy(spawns = listOf(spawn(1, 60.0), spawn(2, -60.0)))
        val first = cache.forMode(two, TeamMode.FOUR_TEAMS)
        assertSame(first, cache.forMode(two, TeamMode.FOUR_TEAMS))
        assertEquals(listOf(1, 2), teams(cache.forMode(two, TeamMode.TWO_TEAMS)))
    }
}
