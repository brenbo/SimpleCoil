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
import org.json.JSONObject

/** Whether everyone has reached their spawn for the start: [atSpawn] of [of] players are there. */
data class Readiness(val ready: Boolean, val atSpawn: Int, val of: Int) {
    fun toJson(): JSONObject = JSONObject().put("ready", ready).put("at", atSpawn).put("of", of)

    companion object {
        fun fromJson(json: JSONObject) = Readiness(json.getBoolean("ready"), json.optInt("at"), json.optInt("of"))
    }
}

/**
 * The start with "start at spawn" on (map design: Respawn points), on the dedicated server: the
 * game begins for nobody until every player stands in one of their own spawns. A player whose
 * phone hasn't reported a position since [GRACE_MILLIS] after the start is left out, so one
 * broken GPS can't hold everyone forever. Once everyone is there, it stays released for the game.
 */
class StartGate(private val startMillis: Long) {
    private var released = false

    fun check(nowMillis: Long, config: FieldConfig?, players: Map<Int, PlayerInfo>, freeForAll: Boolean): Readiness {
        if (released || config == null || !config.spawnRules.startAtSpawn || config.spawns.isEmpty()) return Readiness(true, 0, 0)
        val radius = config.spawnRules.radiusMeters
        val waitingForGps = nowMillis - startMillis < GRACE_MILLIS
        // Players with no position count only while they still might get one
        val counted = players.filterValues { it.point != null || waitingForGps }
        val there = counted.count { (_, p) ->
            val at = p.point ?: return@count false
            config.spawns.any { it.isFor(p.team, freeForAll) && GeoMath.distanceMeters(at, it.point) <= radius }
        }
        val ready = there == counted.size
        if (ready) released = true
        return Readiness(ready, there, counted.size)
    }

    companion object {
        /** How long the start waits for a phone that hasn't reported where it is. */
        const val GRACE_MILLIS = 30_000L
    }
}
