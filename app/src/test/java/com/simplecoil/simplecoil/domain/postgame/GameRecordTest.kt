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

package com.simplecoil.simplecoil.domain.postgame

import com.simplecoil.simplecoil.domain.loadout.LoadoutRules
import com.simplecoil.simplecoil.domain.loadout.PostGameMap
import com.simplecoil.simplecoil.domain.map.LatLon
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameRecordTest {

    private val a = LatLon(39.0, -77.0)
    private val b = LatLon(39.0005, -77.0005)

    private fun recorded(): GameRecorder = GameRecorder().apply {
        start(10_000)
        sample(10_000, mapOf(1 to Triple("Ann", 1, a), 2 to Triple("Bob", 2, b)))
        sample(13_000, mapOf(1 to Triple("Ann", 1, b), 2 to Triple("Bob", 2, null)))
        kill(1, 2, b, a, 55.5, 14_000)
        stop(70_000)
    }

    @Test
    fun aGameIsRecordedWithNamesKillsAndPaths() {
        val record = recorded().record(100_000, withTrack = true)!!
        assertEquals(mapOf(1 to "Ann", 2 to "Bob"), record.names)
        assertEquals(mapOf(1 to 1, 2 to 2), record.teams)
        assertEquals(listOf(Kill(1, 2, b, a, 55.5, 4)), record.kills)
        assertEquals(3, record.track.size)
        // Its length is to when it ended, not now
        assertEquals(60, record.lengthSeconds)
    }

    @Test
    fun killsOnlyLeavesOutThePaths() {
        assertTrue(recorded().record(100_000, withTrack = false)!!.track.isEmpty())
    }

    @Test
    fun nothingIsRecordedOutsideAGame() {
        val recorder = GameRecorder()
        assertNull(recorder.record(0, withTrack = true))
        val done = recorded()
        done.kill(2, 1, a, b, 10.0, 80_000)
        assertEquals(1, done.record(100_000, withTrack = true)!!.kills.size)
    }

    @Test
    fun theReplayShowsWhereEachPlayerWas() {
        val record = recorded().record(100_000, withTrack = true)!!
        assertEquals(mapOf(1 to a, 2 to b), record.positionsAt(1))
        assertEquals(mapOf(1 to b, 2 to b), record.positionsAt(3))
        // Long quiet: off the map
        assertTrue(record.positionsAt(40).isEmpty())
    }

    @Test
    fun theRecordGoesOverTheWire() {
        val record = recorded().record(100_000, withTrack = true)!!
        assertEquals(record, GameRecord.fromJson(JSONObject(record.toJson().toString())))
    }

    @Test
    fun thePostGameMapSettingKeeps() {
        val rules = LoadoutRules(postGameMap = PostGameMap.KILLS)
        assertEquals(PostGameMap.KILLS, LoadoutRules.fromJson(JSONObject(rules.toJson().toString())).postGameMap)
        assertEquals(PostGameMap.FULL, LoadoutRules.fromJson(JSONObject()).postGameMap)
    }
}
