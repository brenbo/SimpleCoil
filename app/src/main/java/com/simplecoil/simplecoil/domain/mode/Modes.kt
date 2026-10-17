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

import com.simplecoil.simplecoil.domain.map.LatLon
import org.json.JSONArray
import org.json.JSONObject

/** The game mode (map design: Game modes). Classic is the game as it always was. */
enum class GameMode(val wireValue: String, val hasObjectives: Boolean) {
    CLASSIC("classic", false),

    /** Capture points A, B, C; each held point scores. */
    DOMINATION("domination", true),

    /** One active point at a time, moving on a timer; holding it scores. */
    HARDPOINT("hardpoint", true);

    companion object {
        fun fromWire(value: String?): GameMode = entries.firstOrNull { it.wireValue == value } ?: CLASSIC
    }
}

/** A capture point: its letter and where it is. */
data class Objective(val id: String, val point: LatLon) {
    companion object {
        /** A, B, C... in the order they are placed. */
        fun letter(index: Int): String = ('A' + index).toString()

        fun listToJson(objectives: List<Objective>): JSONArray = JSONArray().apply {
            objectives.forEach { put(JSONArray().put(it.id).put(it.point.latitude).put(it.point.longitude)) }
        }

        fun listFromJson(json: JSONArray?): List<Objective> {
            json ?: return emptyList()
            return (0 until json.length()).map {
                val o = json.getJSONArray(it)
                Objective(o.getString(0), LatLon(o.getDouble(1), o.getDouble(2)))
            }
        }
    }
}

/** Domination and Hardpoint settings (map design: Domination, Hardpoint). Every value is a server setting. */
data class ObjectiveRules(
    /** How many points; 0 = by field size. Hardpoint: how many spots it moves between. */
    val pointCount: Int = 0,
    /** Fields smaller than this get one point, in square metres. */
    val smallFieldSquareMeters: Double = 2_500.0,
    /** Fields bigger than this get three. */
    val largeFieldSquareMeters: Double = 10_000.0,
    val radiusMeters: Double = 10.0,
    val captureSeconds: Int = 15,
    /** More players on a point take it faster (up to twice as fast). */
    val moreCaptureFaster: Boolean = true,
    val pointsPerTick: Int = 1,
    val tickSeconds: Int = 5,
    val scoreToWin: Int = 200,
    /** Hardpoint: the point moves this often. */
    val moveSeconds: Int = 90,
    /** Hardpoint: the next point shows this long before it opens; 0 = not shown. */
    val showNextSeconds: Int = 20,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("points", pointCount)
        .put("smallField", smallFieldSquareMeters)
        .put("largeField", largeFieldSquareMeters)
        .put("radius", radiusMeters)
        .put("capture", captureSeconds)
        .put("moreFaster", moreCaptureFaster)
        .put("perTick", pointsPerTick)
        .put("tick", tickSeconds)
        .put("toWin", scoreToWin)
        .put("move", moveSeconds)
        .put("showNext", showNextSeconds)

    fun clamped(): ObjectiveRules = copy(
        pointCount = pointCount.coerceIn(0, 5),
        smallFieldSquareMeters = smallFieldSquareMeters.coerceIn(100.0, 1_000_000.0),
        largeFieldSquareMeters = largeFieldSquareMeters.coerceIn(smallFieldSquareMeters.coerceIn(100.0, 1_000_000.0), 1_000_000.0),
        radiusMeters = radiusMeters.coerceIn(8.0, 30.0),
        captureSeconds = captureSeconds.coerceIn(5, 60),
        pointsPerTick = pointsPerTick.coerceIn(1, 10),
        tickSeconds = tickSeconds.coerceIn(1, 10),
        scoreToWin = scoreToWin.coerceIn(50, 1_000),
        moveSeconds = moveSeconds.coerceIn(30, 300),
        showNextSeconds = showNextSeconds.coerceIn(0, 120),
    )

    companion object {
        /**
         * What picking [mode] loads (map design: Mode first). Hardpoint scores every second
         * for the one point, so a held point wins in a couple of minutes.
         */
        fun defaultsFor(mode: GameMode): ObjectiveRules = when (mode) {
            GameMode.HARDPOINT -> ObjectiveRules(tickSeconds = 1, scoreToWin = 150)
            else -> ObjectiveRules()
        }

        fun fromJson(json: JSONObject?): ObjectiveRules {
            val d = ObjectiveRules()
            if (json == null) return d
            return ObjectiveRules(
                pointCount = json.optInt("points", d.pointCount),
                smallFieldSquareMeters = json.optDouble("smallField", d.smallFieldSquareMeters),
                largeFieldSquareMeters = json.optDouble("largeField", d.largeFieldSquareMeters),
                radiusMeters = json.optDouble("radius", d.radiusMeters),
                captureSeconds = json.optInt("capture", d.captureSeconds),
                moreCaptureFaster = json.optBoolean("moreFaster", d.moreCaptureFaster),
                pointsPerTick = json.optInt("perTick", d.pointsPerTick),
                tickSeconds = json.optInt("tick", d.tickSeconds),
                scoreToWin = json.optInt("toWin", d.scoreToWin),
                moveSeconds = json.optInt("move", d.moveSeconds),
                showNextSeconds = json.optInt("showNext", d.showNextSeconds),
            ).clamped()
        }
    }
}

/** One capture point as a game stands: who holds it, who is taking it and how far along. */
data class ObjectiveState(
    val id: String,
    val point: LatLon,
    /** The team holding it; 0 for nobody. */
    val owner: Int,
    /** The team taking it; 0 for nobody. */
    val capturing: Int,
    /** 0 to 1. */
    val progress: Double,
    /** Players from more than one team on it: nothing moves. */
    val contested: Boolean,
    /** Hardpoint: the point that counts now (always true in Domination). */
    val active: Boolean,
    /** Hardpoint: the point that opens next, shown ahead of time. */
    val next: Boolean,
)

/** An objective game as it stands, as the server sends it to the phones every second. */
data class ModeState(
    val mode: GameMode,
    val objectives: List<ObjectiveState>,
    /** Score by team (in free for all, by player id). */
    val scores: Map<Int, Int>,
    val scoreToWin: Int,
    val radiusMeters: Double,
    /** The team that won; 0 while the game goes on. */
    val winner: Int,
    /** Hardpoint: when the point moves (server wall clock); 0 for never. */
    val movesAt: Long,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("mode", mode.wireValue)
        .put("objectives", JSONArray().apply {
            objectives.forEach {
                put(JSONArray().put(it.id).put(it.point.latitude).put(it.point.longitude).put(it.owner).put(it.capturing)
                    .put(it.progress).put(if (it.contested) 1 else 0).put(if (it.active) 1 else 0).put(if (it.next) 1 else 0))
            }
        })
        .put("scores", JSONArray().apply { scores.toSortedMap().forEach { (team, score) -> put(JSONArray().put(team).put(score)) } })
        .put("toWin", scoreToWin)
        .put("radius", radiusMeters)
        .put("winner", winner)
        .put("movesAt", movesAt)

    companion object {
        fun fromJson(json: JSONObject): ModeState {
            val objectives = json.getJSONArray("objectives")
            val scores = json.getJSONArray("scores")
            return ModeState(
                mode = GameMode.fromWire(json.optString("mode")),
                objectives = (0 until objectives.length()).map {
                    val o = objectives.getJSONArray(it)
                    ObjectiveState(o.getString(0), LatLon(o.getDouble(1), o.getDouble(2)), o.getInt(3), o.getInt(4),
                        o.getDouble(5), o.getInt(6) == 1, o.getInt(7) == 1, o.getInt(8) == 1)
                },
                scores = (0 until scores.length()).associate { scores.getJSONArray(it).let { s -> s.getInt(0) to s.getInt(1) } },
                scoreToWin = json.optInt("toWin", ObjectiveRules().scoreToWin),
                radiusMeters = json.optDouble("radius", ObjectiveRules().radiusMeters),
                winner = json.optInt("winner", 0),
                movesAt = json.optLong("movesAt", 0L),
            )
        }
    }
}
