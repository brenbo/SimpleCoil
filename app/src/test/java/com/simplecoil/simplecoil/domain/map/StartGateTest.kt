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

import com.simplecoil.simplecoil.domain.mode.PlayerInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartGateTest {

    private val center = LatLon(39.0, -77.0)
    private val red = GeoMath.offset(center, 0.0, -50.0)
    private val blue = GeoMath.offset(center, 0.0, 50.0)
    private val field = FieldConfig(spawns = listOf(Spawn(1, red), Spawn(2, blue)), spawnRules = SpawnRules(startAtSpawn = true))

    @Test
    fun nobodyStartsUntilEveryoneIsAtTheirSpawn() {
        val gate = StartGate(0)
        var r = gate.check(1_000, field, mapOf(1 to PlayerInfo(1, red, true), 2 to PlayerInfo(2, center, true)), freeForAll = false)
        assertFalse(r.ready)
        assertEquals(Readiness(false, 1, 2), r)
        // Standing in the other team's spawn doesn't count
        r = gate.check(2_000, field, mapOf(1 to PlayerInfo(1, red, true), 2 to PlayerInfo(2, red, true)), freeForAll = false)
        assertFalse(r.ready)
        r = gate.check(3_000, field, mapOf(1 to PlayerInfo(1, red, true), 2 to PlayerInfo(2, blue, true)), freeForAll = false)
        assertTrue(r.ready)
        // Once everyone was there, it stays open
        assertTrue(gate.check(4_000, field, mapOf(1 to PlayerInfo(1, center, true)), freeForAll = false).ready)
    }

    @Test
    fun aPhoneWithNoGpsHoldsTheStartOnlyForAWhile() {
        val gate = StartGate(0)
        val players = mapOf(1 to PlayerInfo(1, red, true), 2 to PlayerInfo(2, null, true))
        assertFalse(gate.check(10_000, field, players, freeForAll = false).ready)
        assertTrue(gate.check(StartGate.GRACE_MILLIS + 1, field, players, freeForAll = false).ready)
    }

    @Test
    fun withoutStartAtSpawnItIsOpen() {
        val gate = StartGate(0)
        assertTrue(gate.check(0, field.copy(spawnRules = SpawnRules(startAtSpawn = false)), mapOf(1 to PlayerInfo(1, center, true)), false).ready)
        assertTrue(gate.check(0, null, emptyMap(), false).ready)
    }
}
