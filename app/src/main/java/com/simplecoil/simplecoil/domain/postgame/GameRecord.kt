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

import com.simplecoil.simplecoil.domain.map.LatLon
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** An elimination with where both players stood, [atSeconds] into the game. */
data class Kill(val shooter: Int, val victim: Int, val from: LatLon, val to: LatLon, val meters: Double, val atSeconds: Int)

/** Where [player] was, [atSeconds] into the game. */
data class TrackPoint(val atSeconds: Int, val player: Int, val point: LatLon)

/**
 * A finished game for the post-game map (map design: Longest shot and post-game stats): who
 * played, each measured elimination, and everyone's path. [track] is empty when the host only
 * keeps the kills.
 */
data class GameRecord(
    val names: Map<Int, String>,
    /** Each player's side: their team, or in free for all, their own id. */
    val teams: Map<Int, Int>,
    val kills: List<Kill>,
    val track: List<TrackPoint>,
    val lengthSeconds: Int,
) {
    /** Each player's position at [seconds]: where they last reported, if within a short while. */
    fun positionsAt(seconds: Int): Map<Int, LatLon> {
        val result = HashMap<Int, LatLon>()
        val seen = HashMap<Int, Int>()
        for (p in track) {
            if (p.atSeconds > seconds) break
            result[p.player] = p.point
            seen[p.player] = p.atSeconds
        }
        // Someone not heard from in a while has gone quiet: leave them off
        return result.filterKeys { seconds - (seen[it] ?: 0) <= STALE_SECONDS }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("length", lengthSeconds)
        .put("players", JSONArray().apply {
            names.keys.union(teams.keys).sorted().forEach { id -> put(JSONArray().put(id).put(names[id] ?: "").put(teams[id] ?: id)) }
        })
        .put("kills", JSONArray().apply {
            kills.forEach { k ->
                put(JSONArray().put(k.shooter).put(k.victim).put(round(k.from.latitude)).put(round(k.from.longitude))
                    .put(round(k.to.latitude)).put(round(k.to.longitude)).put(Math.round(k.meters * 10) / 10.0).put(k.atSeconds))
            }
        })
        // Compact: one array per sample, coordinates to about 10 cm
        .put("track", JSONArray().apply {
            track.forEach { put(JSONArray().put(it.atSeconds).put(it.player).put(round(it.point.latitude)).put(round(it.point.longitude))) }
        })

    companion object {
        /** Gaps longer than this in a player's track hide them from the replay. */
        const val STALE_SECONDS = 20

        private fun round(degrees: Double): Double = String.format(Locale.ROOT, "%.6f", degrees).toDouble()

        fun fromJson(json: JSONObject): GameRecord {
            val players = json.getJSONArray("players")
            val names = HashMap<Int, String>()
            val teams = HashMap<Int, Int>()
            for (i in 0 until players.length()) {
                val p = players.getJSONArray(i)
                names[p.getInt(0)] = p.getString(1)
                teams[p.getInt(0)] = p.getInt(2)
            }
            val kills = json.getJSONArray("kills")
            val track = json.getJSONArray("track")
            return GameRecord(
                names = names, teams = teams,
                kills = (0 until kills.length()).map {
                    val k = kills.getJSONArray(it)
                    Kill(k.getInt(0), k.getInt(1), LatLon(k.getDouble(2), k.getDouble(3)), LatLon(k.getDouble(4), k.getDouble(5)),
                        k.getDouble(6), k.getInt(7))
                },
                track = (0 until track.length()).map {
                    val t = track.getJSONArray(it)
                    TrackPoint(t.getInt(0), t.getInt(1), LatLon(t.getDouble(2), t.getDouble(3)))
                },
                lengthSeconds = json.optInt("length", 0),
            )
        }
    }
}

/**
 * Records a game on the dedicated server as it is played: the kills as they are measured, and,
 * if asked, everyone's position every few seconds. Thread-safe.
 */
class GameRecorder {
    private var startMillis = 0L
    private var endMillis = 0L
    private val kills = ArrayList<Kill>()
    private val track = ArrayList<TrackPoint>()
    private val names = HashMap<Int, String>()
    private val teams = HashMap<Int, Int>()
    private var recording = false

    @Synchronized
    fun start(nowMillis: Long) {
        startMillis = nowMillis
        endMillis = 0L
        kills.clear()
        track.clear()
        names.clear()
        teams.clear()
        recording = true
    }

    @Synchronized
    fun stop(nowMillis: Long) {
        if (recording) endMillis = nowMillis
        recording = false
    }

    private fun seconds(nowMillis: Long) = ((nowMillis - startMillis) / 1000).toInt()

    @Synchronized
    fun kill(shooter: Int, victim: Int, from: LatLon, to: LatLon, meters: Double, nowMillis: Long) {
        if (recording) kills += Kill(shooter, victim, from, to, meters, seconds(nowMillis))
    }

    /** Everyone's position now, with their name and side. */
    @Synchronized
    fun sample(nowMillis: Long, players: Map<Int, Triple<String, Int, LatLon?>>) {
        if (!recording) return
        val at = seconds(nowMillis)
        for ((id, p) in players) {
            names[id] = p.first
            teams[id] = p.second
            p.third?.let { track += TrackPoint(at, id, it) }
        }
    }

    /** The game so far (or as it ended); null before any game. [withTrack] false leaves out the paths. */
    @Synchronized
    fun record(nowMillis: Long, withTrack: Boolean): GameRecord? {
        if (startMillis == 0L) return null
        val end = if (recording) nowMillis else endMillis
        return GameRecord(names.toMap(), teams.toMap(), kills.toList(), if (withTrack) track.toList() else emptyList(), seconds(end))
    }
}
