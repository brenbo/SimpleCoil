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

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

/** A respawn point (map design: Respawn points): [team] 1-4, or 0 for anyone (free for all). */
data class Spawn(val team: Int, val point: LatLon) {
    /** True if a player on [team] respawns here; in free for all every spawn is everyone's. */
    fun isFor(team: Int, freeForAll: Boolean): Boolean = this.team == ANYONE || freeForAll || this.team == team

    companion object {
        const val ANYONE = 0

        fun listToJson(spawns: List<Spawn>): JSONArray = JSONArray().apply {
            spawns.forEach { put(JSONArray().put(it.team).put(it.point.latitude).put(it.point.longitude)) }
        }

        fun listFromJson(json: JSONArray?): List<Spawn> {
            json ?: return emptyList()
            return (0 until json.length()).map {
                val s = json.getJSONArray(it)
                Spawn(s.getInt(0), LatLon(s.getDouble(1), s.getDouble(2)))
            }
        }
    }
}

/** The respawn settings (map design: Respawn points). Every value is a server setting. */
data class SpawnRules(
    /** Respawning takes walking back to one of your team's spawns; off = respawn anywhere. */
    val walkBack: Boolean = true,
    /** The game's start also waits until each player is at a spawn. */
    val startAtSpawn: Boolean = false,
    /** Seconds to stand in a spawn before it counts. */
    val secondsInSpawn: Int = 3,
    /** How big a spawn is. */
    val radiusMeters: Double = 10.0,
    /** How many spawns Auto-place puts down for each team (free for all: in all). */
    val perTeam: Int = 1,
    /** Auto-place keeps enemy spawns this far apart, as a share of the field's width. */
    val enemySpawnShare: Double = 0.5,
    /** Moving spawns avoid spots with an enemy this close right now. */
    val enemyClearMeters: Double = 20.0,
    /** Spawns move every this many minutes during a game; 0 = never. */
    val moveMinutes: Int = 0,
    /** How long a moved spawn takes to fade out while the new one fades in. */
    val crossfadeSeconds: Int = 10,
    /** A moved spawn still works this long, for players already walking to it. */
    val oldSpawnGraceSeconds: Int = 15,
    /** The respawn shield ends as soon as the player leaves their spawn. */
    val shieldOnlyInSpawn: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("walkBack", walkBack)
        .put("startAtSpawn", startAtSpawn)
        .put("secondsInSpawn", secondsInSpawn)
        .put("radius", radiusMeters)
        .put("perTeam", perTeam)
        .put("enemySpawnShare", enemySpawnShare)
        .put("enemyClear", enemyClearMeters)
        .put("moveMinutes", moveMinutes)
        .put("crossfade", crossfadeSeconds)
        .put("oldGrace", oldSpawnGraceSeconds)
        .put("shieldOnlyInSpawn", shieldOnlyInSpawn)

    /** These rules with every value inside the range the server offers. */
    fun clamped(): SpawnRules = copy(
        secondsInSpawn = secondsInSpawn.coerceIn(0, 30),
        radiusMeters = radiusMeters.coerceIn(5.0, 30.0),
        perTeam = perTeam.coerceIn(1, 4),
        enemySpawnShare = enemySpawnShare.coerceIn(0.0, 0.9),
        enemyClearMeters = enemyClearMeters.coerceIn(0.0, 50.0),
        moveMinutes = moveMinutes.coerceIn(0, 15),
        crossfadeSeconds = crossfadeSeconds.coerceIn(0, 30),
        oldSpawnGraceSeconds = oldSpawnGraceSeconds.coerceIn(0, 60),
    )

    companion object {
        fun fromJson(json: JSONObject?): SpawnRules {
            val d = SpawnRules()
            if (json == null) return d
            return SpawnRules(
                walkBack = json.optBoolean("walkBack", d.walkBack),
                startAtSpawn = json.optBoolean("startAtSpawn", d.startAtSpawn),
                secondsInSpawn = json.optInt("secondsInSpawn", d.secondsInSpawn),
                radiusMeters = json.optDouble("radius", d.radiusMeters),
                perTeam = json.optInt("perTeam", d.perTeam),
                enemySpawnShare = json.optDouble("enemySpawnShare", d.enemySpawnShare),
                enemyClearMeters = json.optDouble("enemyClear", d.enemyClearMeters),
                moveMinutes = json.optInt("moveMinutes", d.moveMinutes),
                crossfadeSeconds = json.optInt("crossfade", d.crossfadeSeconds),
                oldSpawnGraceSeconds = json.optInt("oldGrace", d.oldSpawnGraceSeconds),
                shieldOnlyInSpawn = json.optBoolean("shieldOnlyInSpawn", d.shieldOnlyInSpawn),
            ).clamped()
        }
    }
}

/**
 * Auto-place (map design: Respawn points, Placement): picks spawn spots that pass every rule,
 * giving each team its own side of the field. It tries a few hundred random spots, throws out
 * any that break a rule, and keeps the best. On a field too small for the rules it loosens the
 * distances step by step instead of failing, and says so.
 */
object SpawnPlanner {
    /** What Auto-place came up with; [relaxed] if it had to loosen the rules. */
    data class Result(val spawns: List<Spawn>, val relaxed: Boolean)

    const val CANDIDATES = 400

    /** Spawns of the same team stay at least this share of the field's width apart. */
    private const val OWN_SPAWN_SHARE = 0.25

    /** How far each loosening step shrinks the distance rules. */
    private const val RELAX_STEP = 0.7
    private const val RELAX_STEPS = 6

    /** A moved spawn lands at least this share of the field's width from where it was. */
    private const val MOVE_SHARE = 0.25

    /** How much chance weighs in where a moved spawn lands, as a share of the field's width. */
    private const val MOVE_CHANCE = 0.4

    /**
     * Spawns for [teams] (1-4; just 0 for free for all), [SpawnRules.perTeam] each, inside
     * [border] and [edgeBufferMeters] in from it. [enemies] are players' positions by team, for
     * spawns that move mid-game (a spot with an enemy close by is skipped). Same [random], same
     * result.
     */
    fun place(
        border: Border,
        teams: List<Int>,
        rules: SpawnRules,
        edgeBufferMeters: Double,
        enemies: Map<Int, List<LatLon>> = emptyMap(),
        /** Where the spawns were before a move: new ones land well away from these. */
        previous: List<Spawn> = emptyList(),
        random: Random = Random.Default,
        /** Spots to keep clear of, each with how far (capture points): spawns never land on them. */
        keepClear: List<Pair<LatLon, Double>> = emptyList(),
    ): Result {
        val bounds = border.bounds
        val center = bounds.center
        val width = fieldWidth(border)
        val candidates = (0 until CANDIDATES).map {
            LatLon(bounds.south + random.nextDouble() * (bounds.north - bounds.south),
                bounds.west + random.nextDouble() * (bounds.east - bounds.west))
        }.filter { border.contains(it) }
        if (candidates.isEmpty()) return Result(emptyList(), true)

        var scale = 1.0
        repeat(RELAX_STEPS + 1) { step ->
            val placed = tryPlace(border, teams, rules, edgeBufferMeters * scale, width, center, candidates, enemies, previous, scale, random, keepClear)
            if (placed != null) return Result(placed, step > 0)
            scale *= RELAX_STEP
        }
        // Still nothing: spread them out with no distance rules at all, then (a small field whose
        // capture points cover it) let them touch the points too, rather than a team going without
        return Result(tryPlace(border, teams, rules, 0.0, width, center, candidates, emptyMap(), emptyList(), 0.0, random, keepClear)
            ?: tryPlace(border, teams, rules, 0.0, width, center, candidates, emptyMap(), emptyList(), 0.0, random, emptyList())
            ?: emptyList(), true)
    }

    /** The field's width: a circle's diameter, or the longer side of a polygon's box. */
    fun fieldWidth(border: Border): Double = when (border) {
        is Border.Circle -> border.radiusMeters * 2
        is Border.Polygon -> {
            val b = border.bounds
            max(GeoMath.distanceMeters(LatLon(b.south, b.west), LatLon(b.north, b.west)),
                GeoMath.distanceMeters(LatLon(b.south, b.west), LatLon(b.south, b.east)))
        }
    }

    private fun tryPlace(
        border: Border,
        teams: List<Int>,
        rules: SpawnRules,
        edgeBuffer: Double,
        width: Double,
        center: LatLon,
        candidates: List<LatLon>,
        enemies: Map<Int, List<LatLon>>,
        previous: List<Spawn>,
        scale: Double,
        random: Random,
        keepClear: List<Pair<LatLon, Double>>,
    ): List<Spawn>? {
        val placed = ArrayList<Spawn>()
        // A capture point always stays clear, however far the other rules loosen
        val usable = candidates.filter { spot ->
            border.distanceToEdgeMeters(spot) >= edgeBuffer + rules.radiusMeters * scale &&
                keepClear.all { (point, clear) -> GeoMath.distanceMeters(spot, point) >= clear + rules.radiusMeters }
        }
        // Each team leans toward its own side: 2 teams at opposite ends, 4 at the four sides
        val count = teams.size.coerceAtLeast(1)
        for (round in 0 until rules.perTeam) {
            for ((index, team) in teams.withIndex()) {
                val angle = 2 * PI * index / count
                val dirEast = cos(angle)
                val dirNorth = sin(angle)
                val best = usable.filter { spot ->
                    placed.all { other ->
                        val apart = GeoMath.distanceMeters(spot, other.point)
                        val minimum = if (other.team == team) width * OWN_SPAWN_SHARE else width * rules.enemySpawnShare
                        apart >= minimum * scale && apart >= rules.radiusMeters * 2
                    } && previous.filter { it.team == team }.all {
                        GeoMath.distanceMeters(spot, it.point) >= max(width * MOVE_SHARE, rules.radiusMeters * 2) * scale
                    } && enemies.filterKeys { it != team || team == Spawn.ANYONE }.values.flatten().all {
                        GeoMath.distanceMeters(spot, it) >= rules.enemyClearMeters * scale
                    }
                }.maxByOrNull { spot ->
                    val (east, north) = GeoMath.toLocal(center, spot)
                    // Toward the team's own side, and away from every spawn so far
                    val toward = if (team == Spawn.ANYONE) 0.0 else (east * dirEast + north * dirNorth)
                    val spread = placed.minOfOrNull { GeoMath.distanceMeters(spot, it.point) } ?: 0.0
                    // A move gets some chance in it, so spawns don't settle into a pattern
                    val chance = if (previous.isEmpty()) 0.0 else random.nextDouble() * width * MOVE_CHANCE
                    toward + spread * 0.5 + chance + rotationTieBreak(east, north, angle)
                } ?: return null
                placed += Spawn(team, best)
            }
        }
        return placed
    }

    /** A tiny nudge so equal scores still pick a spot on the team's side. */
    private fun rotationTieBreak(east: Double, north: Double, angle: Double): Double =
        -kotlin.math.abs(atan2(north, east) - angle) * 1e-6

    /**
     * [base] with its spawns moved for the next part of a game (map design: Moving spawns): the
     * same teams and count, each well away from where it was, away from [enemies] standing
     * nearby, and numbered [generation] so phones fade old into new. Null if there is nothing to
     * move (no border, no spawns) or nowhere to move it.
     */
    fun moved(base: FieldConfig, current: List<Spawn>, generation: Int, enemies: Map<Int, List<LatLon>>, random: Random = Random.Default): FieldConfig? {
        val border = base.border ?: return null
        if (current.isEmpty()) return null
        val teams = current.map { it.team }.distinct().sorted()
        val perTeam = current.groupingBy { it.team }.eachCount().values.max().coerceIn(1, 4)
        val result = place(border, teams, base.spawnRules.copy(perTeam = perTeam), base.rules.edgeBufferMeters, enemies, current, random, objectiveClearance(base))
        if (result.spawns.isEmpty()) return null
        return base.copy(spawns = result.spawns, spawnGeneration = generation)
    }

    /** Where spawns keep clear of: [base]'s capture points, by their radius and a gap for GPS error. */
    fun objectiveClearance(base: FieldConfig): List<Pair<LatLon, Double>> =
        base.objectives.map { it.point to base.objectiveRules.radiusMeters + com.simplecoil.simplecoil.domain.mode.ObjectivePlanner.GAP_METERS }

    /** The teams Auto-place serves for [teamCount] teams (0 = free for all). */
    fun teamsFor(teamCount: Int): List<Int> = if (teamCount <= 0) listOf(Spawn.ANYONE) else (1..teamCount).toList()
}
