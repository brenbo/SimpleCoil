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

import com.simplecoil.simplecoil.domain.map.Border
import com.simplecoil.simplecoil.domain.map.GeoMath
import com.simplecoil.simplecoil.domain.map.LatLon
import com.simplecoil.simplecoil.domain.map.Spawn
import org.json.JSONObject
import kotlin.random.Random

/** Extraction settings (map design: Extraction). */
data class ExtractionRules(
    val lootPoints: Int = 6,
    val collectSeconds: Int = 10,
    /** The extraction zones open this long after the start. */
    val opensAfterMinutes: Int = 6,
    val zones: Int = 1,
    /** First team to bank this much wins; 0 = play to the game limits. */
    val lootToWin: Int = 0,
    val radiusMeters: Double = 8.0,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("loot", lootPoints).put("collect", collectSeconds).put("opens", opensAfterMinutes).put("zones", zones)
        .put("toWin", lootToWin).put("radius", radiusMeters)

    fun clamped(): ExtractionRules = copy(
        lootPoints = lootPoints.coerceIn(3, 20),
        collectSeconds = collectSeconds.coerceIn(3, 30),
        opensAfterMinutes = opensAfterMinutes.coerceIn(0, 60),
        zones = zones.coerceIn(1, 3),
        lootToWin = lootToWin.coerceIn(0, 500),
        radiusMeters = radiusMeters.coerceIn(ObjectiveRules.MIN_RADIUS_METERS, 30.0),
    )

    companion object {
        fun fromJson(json: JSONObject?): ExtractionRules {
            val d = ExtractionRules()
            if (json == null) return d
            return ExtractionRules(json.optInt("loot", d.lootPoints), json.optInt("collect", d.collectSeconds),
                json.optInt("opens", d.opensAfterMinutes), json.optInt("zones", d.zones), json.optInt("toWin", d.lootToWin),
                json.optDouble("radius", d.radiusMeters)).clamped()
        }
    }
}

/** A pile of loot on the field: how much it is worth. */
data class LootState(val id: Int, val point: LatLon, val value: Int)

/** Extraction's state for the phones. */
data class ExtractionState(
    val loot: List<LootState>,
    val zones: List<LatLon>,
    /** When the zones open (server wall clock); they are open from then on. */
    val opensAt: Long,
    /** Loot each player carries, by player id. */
    val carrying: Map<Int, Int>,
    /** Player collecting something now (loot or extracting), with how far along, 0 to 1. */
    val progress: Map<Int, Double>,
)

/**
 * Extraction on the dedicated server (map design: Extraction): loot lies around the field;
 * standing on a pile for the collect time picks it up, and a new pile appears elsewhere. Loot
 * only counts once its carrier stands in an open extraction zone for the same time; an
 * eliminated carrier drops everything where they fell.
 */
class ExtractionEngine(
    private val rules: ExtractionRules,
    private val border: Border,
    private val avoid: List<Pair<LatLon, Double>>,
    private val startMillis: Long,
    private val random: Random = Random.Default,
) {
    private val loot = ArrayList<LootState>()
    private val zones = ArrayList<LatLon>()
    private val carrying = HashMap<Int, Int>()
    private val banked = HashMap<Int, Int>()
    private val lastPoint = HashMap<Int, LatLon>()
    /** Who is standing on what, since when: "loot:<id>" or "zone". */
    private val dwell = HashMap<Int, Pair<String, Long>>()
    private var nextId = 1
    private var winner = 0
    private val opensAt = startMillis + rules.opensAfterMinutes * 60_000L

    init {
        // Zones far from each other and from spawns, then the loot clear of them
        repeat(rules.zones) { spot(emptyList())?.let { zones += it } }
        repeat(rules.lootPoints) { addLoot(1 + random.nextInt(MAX_PILE)) }
    }

    private fun spot(more: List<Pair<LatLon, Double>>): LatLon? {
        val clear = avoid + more + zones.map { it to SPREAD_METERS } + loot.map { it.point to rules.radiusMeters * 3 }
        return com.simplecoil.simplecoil.domain.loadout.LiveField.dropSpot(border, rules.radiusMeters, clear, random)
    }

    private fun addLoot(value: Int, at: LatLon? = null) {
        val point = at ?: spot(emptyList()) ?: return
        loot += LootState(nextId++, point, value)
    }

    fun onEliminated(playerId: Int) {
        val carried = carrying.remove(playerId) ?: return
        dwell.remove(playerId)
        lastPoint[playerId]?.let { addLoot(carried, it) }
    }

    fun tick(nowMillis: Long, players: Map<Int, PlayerInfo>): ModeState {
        val progress = HashMap<Int, Double>()
        if (winner == 0) {
            val open = nowMillis >= opensAt
            for ((id, p) in players) {
                val at = p.point
                if (at == null || !p.alive) {
                    dwell.remove(id)
                    continue
                }
                lastPoint[id] = at
                val pile = loot.firstOrNull { GeoMath.distanceMeters(at, it.point) <= rules.radiusMeters }
                val inZone = open && (carrying[id] ?: 0) > 0 && zones.any { GeoMath.distanceMeters(at, it) <= rules.radiusMeters }
                val target = when {
                    pile != null -> "loot:${pile.id}"
                    inZone -> "zone"
                    else -> null
                }
                if (target == null) {
                    dwell.remove(id)
                    continue
                }
                val since = dwell[id]?.takeIf { it.first == target }?.second ?: nowMillis.also { dwell[id] = target to it }
                val f = (nowMillis - since) / (rules.collectSeconds * 1000.0)
                if (f < 1) {
                    progress[id] = f
                    continue
                }
                dwell.remove(id)
                if (pile != null) {
                    loot.remove(pile)
                    carrying[id] = (carrying[id] ?: 0) + pile.value
                    addLoot(1 + random.nextInt(MAX_PILE))
                } else {
                    banked[p.team] = (banked[p.team] ?: 0) + (carrying.remove(id) ?: 0)
                }
            }
            if (rules.lootToWin > 0) banked.filterValues { it >= rules.lootToWin }.maxByOrNull { it.value }?.let { winner = it.key }
        }
        val teams = players.values.map { it.team }.toSet()
        return ModeState(
            mode = GameMode.EXTRACTION, objectives = emptyList(), scores = teams.associateWith { banked[it] ?: 0 } + banked,
            scoreToWin = rules.lootToWin, radiusMeters = rules.radiusMeters, winner = winner, movesAt = 0L,
            extraction = ExtractionState(loot.toList(), zones, opensAt, carrying.toMap(), progress),
        )
    }

    private companion object {
        const val MAX_PILE = 3
        const val SPREAD_METERS = 40.0
    }
}

/**
 * A flag base for each of [teams] teams (two to four): the teams' first spawns, or, without
 * them, spots spread round the field, clear of each other.
 */
fun flagBases(border: Border, spawns: List<Spawn>, edgeBufferMeters: Double, random: Random = Random.Default, teams: Int = 2): Map<Int, LatLon> {
    val numbers = (1..teams.coerceIn(2, 4)).toList()
    val own = numbers.associateWith { t -> spawns.firstOrNull { it.team == t }?.point }
    val have = own.filterValues { it != null }.mapValues { it.value!! }
    if (have.size == numbers.size) return have
    // The teams with no spawn of their own get a base placed away from the others
    val placed = com.simplecoil.simplecoil.domain.map.SpawnPlanner.place(border, numbers - have.keys,
        com.simplecoil.simplecoil.domain.map.SpawnRules(perTeam = 1), edgeBufferMeters,
        enemies = have.mapValues { listOf(it.value) }, random = random).spawns
    return have + placed.associate { it.team to it.point }
}
