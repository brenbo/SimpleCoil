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

import com.simplecoil.simplecoil.domain.map.GeoCircle
import com.simplecoil.simplecoil.domain.team.TeamMode
import com.simplecoil.simplecoil.domain.map.LatLon
import org.json.JSONArray
import org.json.JSONObject

/**
 * The game mode (map design: Game modes). Classic is the game as it always was; the others the
 * dedicated server runs, and the phones ask it how the game stands ([onServer]).
 */
enum class GameMode(val wireValue: String, val hasObjectives: Boolean, val needsBorder: Boolean = hasObjectives) {
    CLASSIC("classic", false),

    /** Capture points A, B, C; each held point scores. */
    DOMINATION("domination", true),

    /** One active point at a time, moving on a timer; holding it scores. */
    HARDPOINT("hardpoint", true),

    /** A shrinking safe zone; out once eliminated; the last team standing wins. */
    BATTLE_ROYALE("battleroyale", false, needsBorder = true),

    /** Score is the distance of each elimination. */
    MARKSMAN("marksman", false),

    /** Take the other team's flag from its base to yours. */
    CAPTURE_THE_FLAG("ctf", false, needsBorder = true),

    /** Collect loot around the field and bank it at an extraction zone. */
    EXTRACTION("extraction", false, needsBorder = true),

    /** Rounds with no respawns: plant the bomb at a site, or stop it. */
    SEARCH_AND_DESTROY("snd", false, needsBorder = true),

    /** The infected tag survivors, who join them; survivors win by lasting. */
    INFECTION("infection", false);

    /** The server runs it and the phones fetch its state. */
    val onServer: Boolean get() = this != CLASSIC

    /**
     * The team modes it can be played with (the server greys out the rest): Infection and Battle
     * Royale are everyone for themselves; Capture the Flag and Search & Destroy are one team
     * against another; Domination and Hardpoint are teams holding points (two, three or four).
     * Squads go wherever free for all does.
     */
    val teamModes: Set<TeamMode>
        get() = when (this) {
            INFECTION, BATTLE_ROYALE -> setOf(TeamMode.FREE_FOR_ALL, TeamMode.SQUADS)
            CAPTURE_THE_FLAG, SEARCH_AND_DESTROY -> setOf(TeamMode.TWO_TEAMS)
            DOMINATION, HARDPOINT -> setOf(TeamMode.TWO_TEAMS, TeamMode.THREE_TEAMS, TeamMode.FOUR_TEAMS)
            else -> TeamMode.entries.toSet()
        }

    /**
     * The team mode to switch to when this mode is picked: the one it was last played with, if
     * it fits; else, for a mode that needs one, free for all or two teams; else [current].
     */
    fun teamModeFor(remembered: TeamMode?, current: TeamMode): TeamMode = when {
        remembered != null && remembered in teamModes -> remembered
        current in teamModes && teamModes.size == TeamMode.entries.size -> current
        TeamMode.TWO_TEAMS in teamModes -> TeamMode.TWO_TEAMS
        else -> teamModes.first()
    }

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
        radiusMeters = radiusMeters.coerceIn(MIN_RADIUS_METERS, 30.0),
        captureSeconds = captureSeconds.coerceIn(5, 60),
        pointsPerTick = pointsPerTick.coerceIn(1, 10),
        tickSeconds = tickSeconds.coerceIn(1, 10),
        scoreToWin = scoreToWin.coerceIn(50, 1_000),
        moveSeconds = moveSeconds.coerceIn(30, 300),
        showNextSeconds = showNextSeconds.coerceIn(0, 120),
    )

    companion object {
        /** About 5 ft: a point can be as small as a doorway. */
        const val MIN_RADIUS_METERS = 1.5

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
    /** Battle Royale: the zone. */
    val zone: ZoneState? = null,
    /** Battle Royale: the players out for good. */
    val out: Set<Int> = emptySet(),
    /** Capture the Flag: each team's flag. */
    val flags: List<FlagState> = emptyList(),
    /** Capture the Flag with one flag: each team's base, where they bring it to score. */
    val flagBases: Map<Int, LatLon> = emptyMap(),
    /** Extraction: the loot, the zones and who carries what. */
    val extraction: ExtractionState? = null,
    /** Rounds: the round number (it goes up when everyone respawns). */
    val round: Int = 0,
    /** Eliminated players stay out (until the next round). */
    val noRespawn: Boolean = false,
    /** Search & Destroy: the round and the bomb. */
    val bomb: BombState? = null,
    /** The player planting or defusing right now; 0 if nobody. */
    val actor: Int = 0,
    /** Infection: who is infected. */
    val infected: Set<Int> = emptySet(),
    /** Infection: survivors shown on the infected's maps right now, by player id. */
    val revealed: Map<Int, LatLon> = emptyMap(),
    /** Players who joined too late to play now, watching until the next round (Search & Destroy) or game. */
    val sittingOut: Set<Int> = emptySet(),
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
        .apply {
            zone?.let { z ->
                put("zone", JSONObject()
                    .put("current", circleJson(z.current))
                    .put("next", z.next?.let { circleJson(it) })
                    .put("phase", z.phase).put("phases", z.phases).put("shrinking", z.shrinking)
                    .put("changesAt", z.changesAt).put("damage", z.damage).put("tick", z.tickSeconds).put("noRespawn", z.noRespawn))
            }
            if (out.isNotEmpty()) put("out", JSONArray().apply { out.sorted().forEach { put(it) } })
            if (flags.isNotEmpty()) put("flags", JSONArray().apply {
                flags.forEach { f ->
                    put(JSONArray().put(f.team).put(f.base.latitude).put(f.base.longitude).put(f.point.latitude).put(f.point.longitude)
                        .put(f.carrier).put(if (f.dropped) 1 else 0).put(if (f.revealed) 1 else 0).put(f.carrierTeam))
                }
            })
            if (flagBases.isNotEmpty()) put("flagBases", JSONArray().apply {
                flagBases.toSortedMap().forEach { (team, p) -> put(JSONArray().put(team).put(p.latitude).put(p.longitude)) }
            })
            if (round != 0) put("round", round)
            if (noRespawn) put("noRespawn", true)
            if (actor != 0) put("actor", actor)
            bomb?.let { b ->
                put("bomb", JSONObject().put("round", b.round).put("attackers", b.attackers).put("phase", b.phase.wireValue)
                    .put("endsAt", b.endsAt).put("carrier", b.carrier)
                    .put("at", b.bomb?.let { JSONArray().put(it.latitude).put(it.longitude) })
                    .put("planted", b.plantedAt).put("lastWinner", b.lastRoundWinner)
                    .put("out", JSONArray().apply { b.out.sorted().forEach { put(it) } }))
            }
            if (infected.isNotEmpty()) put("infected", JSONArray().apply { infected.sorted().forEach { put(it) } })
            if (sittingOut.isNotEmpty()) put("sitout", JSONArray().apply { sittingOut.sorted().forEach { put(it) } })
            if (revealed.isNotEmpty()) put("revealed", JSONArray().apply {
                revealed.toSortedMap().forEach { (id, p) -> put(JSONArray().put(id).put(p.latitude).put(p.longitude)) }
            })
            extraction?.let { e ->
                put("extraction", JSONObject()
                    .put("loot", JSONArray().apply { e.loot.forEach { put(JSONArray().put(it.id).put(it.point.latitude).put(it.point.longitude).put(it.value)) } })
                    .put("zones", JSONArray().apply { e.zones.forEach { put(JSONArray().put(it.latitude).put(it.longitude)) } })
                    .put("opensAt", e.opensAt)
                    .put("carrying", JSONArray().apply { e.carrying.toSortedMap().forEach { (id, n) -> put(JSONArray().put(id).put(n)) } })
                    .put("progress", JSONArray().apply { e.progress.toSortedMap().forEach { (id, f) -> put(JSONArray().put(id).put(f)) } }))
            }
        }

    companion object {
        private fun circleJson(c: GeoCircle): JSONArray = JSONArray().put(c.center.latitude).put(c.center.longitude).put(c.radiusMeters)

        private fun circleFromJson(a: JSONArray) = GeoCircle(LatLon(a.getDouble(0), a.getDouble(1)), a.getDouble(2))

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
                zone = json.optJSONObject("zone")?.let { z ->
                    ZoneState(circleFromJson(z.getJSONArray("current")), z.optJSONArray("next")?.let { circleFromJson(it) },
                        z.optInt("phase"), z.optInt("phases"), z.optBoolean("shrinking"), z.optLong("changesAt"),
                        z.optInt("damage", 1), z.optInt("tick", 3), z.optBoolean("noRespawn", true))
                },
                out = json.optJSONArray("out")?.let { a -> (0 until a.length()).map { a.getInt(it) }.toSet() } ?: emptySet(),
                flags = json.optJSONArray("flags")?.let { a ->
                    (0 until a.length()).map {
                        val f = a.getJSONArray(it)
                        FlagState(f.getInt(0), LatLon(f.getDouble(1), f.getDouble(2)), LatLon(f.getDouble(3), f.getDouble(4)), f.getInt(5), f.getInt(6) == 1, f.getInt(7) == 1, f.optInt(8, 0))
                    }
                } ?: emptyList(),
                flagBases = json.optJSONArray("flagBases")?.let { a ->
                    (0 until a.length()).associate { a.getJSONArray(it).let { b -> b.getInt(0) to LatLon(b.getDouble(1), b.getDouble(2)) } }
                } ?: emptyMap(),
                round = json.optInt("round", 0),
                noRespawn = json.optBoolean("noRespawn", false),
                actor = json.optInt("actor", 0),
                bomb = json.optJSONObject("bomb")?.let { b ->
                    val out = b.getJSONArray("out")
                    BombState(b.getInt("round"), b.getInt("attackers"), RoundPhase.fromWire(b.optString("phase")), b.getLong("endsAt"),
                        b.getInt("carrier"), b.optJSONArray("at")?.let { LatLon(it.getDouble(0), it.getDouble(1)) },
                        b.optString("planted").takeIf { it.isNotEmpty() && !b.isNull("planted") }, b.getInt("lastWinner"),
                        (0 until out.length()).map { out.getInt(it) }.toSet())
                },
                infected = json.optJSONArray("infected")?.let { a -> (0 until a.length()).map { a.getInt(it) }.toSet() } ?: emptySet(),
                sittingOut = json.optJSONArray("sitout")?.let { a -> (0 until a.length()).map { a.getInt(it) }.toSet() } ?: emptySet(),
                revealed = json.optJSONArray("revealed")?.let { a ->
                    (0 until a.length()).associate { a.getJSONArray(it).let { r -> r.getInt(0) to LatLon(r.getDouble(1), r.getDouble(2)) } }
                } ?: emptyMap(),
                extraction = json.optJSONObject("extraction")?.let { e ->
                    val loot = e.getJSONArray("loot")
                    val zones = e.getJSONArray("zones")
                    val carrying = e.getJSONArray("carrying")
                    val progress = e.getJSONArray("progress")
                    ExtractionState(
                        (0 until loot.length()).map { loot.getJSONArray(it).let { l -> LootState(l.getInt(0), LatLon(l.getDouble(1), l.getDouble(2)), l.getInt(3)) } },
                        (0 until zones.length()).map { zones.getJSONArray(it).let { z -> LatLon(z.getDouble(0), z.getDouble(1)) } },
                        e.getLong("opensAt"),
                        (0 until carrying.length()).associate { carrying.getJSONArray(it).let { c -> c.getInt(0) to c.getInt(1) } },
                        (0 until progress.length()).associate { progress.getJSONArray(it).let { p -> p.getInt(0) to p.getDouble(1) } },
                    )
                },
            )
        }
    }
}
