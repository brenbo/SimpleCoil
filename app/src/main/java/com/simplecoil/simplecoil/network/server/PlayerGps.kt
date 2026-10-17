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

package com.simplecoil.simplecoil.network.server

import com.simplecoil.simplecoil.domain.loadout.Fix
import com.simplecoil.simplecoil.domain.loadout.ShotStats

/** A connected player's GPS as the dedicated server sees it: reporting, or not (and since when). */
data class PlayerGps(val playerId: Int, val name: String, val reporting: Boolean)

/** Whose GPS the server is hearing from, for its main screen. */
object ServerGps {

    /**
     * Each connected player in [players] (id to name), reporting if their last position in
     * [fixes] is recent enough to measure a shot with.
     */
    fun report(players: List<Pair<Int, String>>, fixes: Map<Int, Fix>, nowMillis: Long): List<PlayerGps> =
        players.map { (id, name) ->
            val fix = fixes[id]
            PlayerGps(id, name, fix != null && nowMillis - fix.atMillis <= ShotStats.MAX_FIX_AGE_MILLIS)
        }

    /** How often the report is refreshed. */
    const val REFRESH_MILLIS = 2_000L
}
