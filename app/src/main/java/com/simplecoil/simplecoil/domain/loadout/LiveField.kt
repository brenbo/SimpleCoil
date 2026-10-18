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

package com.simplecoil.simplecoil.domain.loadout

import com.simplecoil.simplecoil.domain.map.Border
import com.simplecoil.simplecoil.domain.map.FieldConfig
import com.simplecoil.simplecoil.domain.map.GeoMath
import com.simplecoil.simplecoil.domain.map.LatLon
import com.simplecoil.simplecoil.domain.map.Spawn
import kotlin.random.Random

/**
 * The field during a game, on the dedicated server (map design: Moving spawns, Drops): the
 * host's saved field, with the spawns as moved so far and the drops lying out. The server serves
 * [config] to the phones in place of the saved field, and is the one place a drop is claimed, so
 * two players can't both get it. Thread-safe.
 */
class LiveField {
    private var base: FieldConfig? = null
    private var spawns: List<Spawn>? = null
    private var spawnGeneration = 0
    private val drops = LinkedHashMap<String, Drop>()
    private var nextId = 1

    /** True between [start] and [stop]. */
    val isActive: Boolean @Synchronized get() = base != null

    /** A game started on [field]. */
    @Synchronized
    fun start(field: FieldConfig) {
        base = field
        spawns = null
        spawnGeneration = 0
        drops.clear()
    }

    @Synchronized
    fun stop() {
        base = null
        spawns = null
        drops.clear()
    }

    /** The field as the phones should see it now; null outside a game. */
    @Synchronized
    fun config(): FieldConfig? {
        val field = base ?: return null
        return field.copy(spawns = spawns ?: field.spawns, spawnGeneration = spawnGeneration, drops = drops.values.toList())
    }

    @Synchronized
    fun currentSpawns(): List<Spawn> = spawns ?: base?.spawns ?: emptyList()

    @Synchronized
    fun moveSpawns(moved: List<Spawn>) {
        spawns = moved
        spawnGeneration++
    }

    /** Announces a drop of [kind] at [point], landing after the warning time. */
    @Synchronized
    fun drop(kind: DropKind, point: LatLon, serverNow: Long): Drop? {
        val rules = base?.loadout ?: return null
        val landsAt = serverNow + rules.dropWarningSeconds * 1000L
        return add(kind, point, landsAt, landsAt + rules.dropLastsMinutes * 60_000L)
    }

    /** A scavenger pickup where a player fell: there at once, for a short while. */
    @Synchronized
    fun scavenge(point: LatLon, serverNow: Long): Drop? {
        val rules = base?.loadout ?: return null
        return add(DropKind.SCAVENGE, point, serverNow, serverNow + rules.scavengeSeconds * 1000L)
    }

    private fun add(kind: DropKind, point: LatLon, landsAt: Long, expiresAt: Long): Drop {
        val drop = Drop("d${nextId++}", kind, point, landsAt, expiresAt)
        drops[drop.id] = drop
        return drop
    }

    /**
     * A player on [team] claims drop [id]. They get its kind, unless it's gone, hasn't landed
     * yet, or a player from another team stands on it too ([others]: last known positions by
     * team). In free for all, [team] is the player's own id and every other player counts.
     */
    @Synchronized
    fun claim(id: String, team: Int, others: Map<Int, List<LatLon>>, serverNow: Long): DropKind? {
        val drop = drops[id] ?: return null
        if (!drop.isLanded(serverNow) || drop.isExpired(serverNow)) return null
        val radius = base?.loadout?.claimRadiusMeters ?: return null
        val contested = others.filterKeys { it != team }.values.flatten().any { GeoMath.distanceMeters(it, drop.point) <= radius }
        if (contested) return null
        drops.remove(id)
        return drop.kind
    }

    /** Takes away expired drops; true if any went. */
    @Synchronized
    fun prune(serverNow: Long): Boolean {
        val gone = drops.values.filter { it.isExpired(serverNow) }
        gone.forEach { drops.remove(it.id) }
        return gone.isNotEmpty()
    }

    companion object {
        /**
         * A random spot for a drop: inside [border], [edgeBufferMeters] in from it, and at least
         * each [keepClear] distance from its point (spawns, other drops), so crates don't pile up
         * on each other or on a spawn. On a field too small for that, the least crowded spot found.
         * Null only if no spot inside the border turns up.
         */
        fun dropSpot(
            border: Border,
            edgeBufferMeters: Double,
            keepClear: List<Pair<LatLon, Double>> = emptyList(),
            random: Random = Random.Default,
        ): LatLon? {
            val b = border.bounds
            var best: LatLon? = null
            var bestScore = Double.NEGATIVE_INFINITY
            repeat(TRIES) {
                val spot = LatLon(b.south + random.nextDouble() * (b.north - b.south), b.west + random.nextDouble() * (b.east - b.west))
                if (!border.contains(spot)) return@repeat
                // Each thing's distance as a share of the room it needs, the edge's too: 1 or more
                // is clear of everything. On a field too small for that, the best spot is the one
                // least crowded by anything, so piles still spread out rather than share a spot
                val edge = if (edgeBufferMeters > 0) border.distanceToEdgeMeters(spot) / edgeBufferMeters else Double.MAX_VALUE
                val score = minOf(edge, keepClear.minOfOrNull { (point, clear) ->
                    if (clear <= 0) Double.MAX_VALUE else GeoMath.distanceMeters(spot, point) / clear
                } ?: Double.MAX_VALUE)
                if (score >= 1) return spot
                if (score > bestScore) {
                    best = spot
                    bestScore = score
                }
            }
            return best
        }

        /** How far apart two drops land, where the field allows. */
        const val DROP_SPACING_METERS = 30.0

        /** How far a drop lands outside a spawn's edge, where the field allows. */
        const val SPAWN_CLEARANCE_METERS = 20.0

        private const val TRIES = 300
    }
}

/** One elimination, measured from both players' GPS (map design: Longest shot). */
data class Shot(val shooterId: Int, val victimId: Int, val shooter: LatLon, val victim: LatLon, val meters: Double)

/** A player's last reported position, and when it arrived (monotonic milliseconds). */
data class Fix(val point: LatLon, val atMillis: Long)

/**
 * Shot distances during a game, on the dedicated server. A distance only counts if both players
 * reported a position recently, and isn't longer than a blaster can shoot (bad GPS). Thread-safe.
 */
class ShotStats(private val maxFixAgeMillis: Long = MAX_FIX_AGE_MILLIS) {
    private val shots = ArrayList<Shot>()

    @Synchronized
    fun clear() = shots.clear()

    /** Records the shot if it can be measured; returns it. */
    @Synchronized
    fun record(shooterId: Int, victimId: Int, shooter: Fix?, victim: Fix?, nowMillis: Long, maxMeters: Double): Shot? {
        if (shooterId == victimId || shooter == null || victim == null) return null
        if (nowMillis - shooter.atMillis > maxFixAgeMillis || nowMillis - victim.atMillis > maxFixAgeMillis) return null
        val meters = GeoMath.distanceMeters(shooter.point, victim.point)
        if (meters > maxMeters) return null
        return Shot(shooterId, victimId, shooter.point, victim.point, meters).also { shots += it }
    }

    @Synchronized
    fun longest(): Shot? = shots.maxByOrNull { it.meters }

    @Synchronized
    fun all(): List<Shot> = shots.toList()

    /** Each shooter's longest shot, longest first. */
    @Synchronized
    fun bestByShooter(): List<Shot> =
        shots.groupBy { it.shooterId }.values.map { mine -> mine.maxBy { it.meters } }.sortedByDescending { it.meters }

    companion object {
        /** Phones report at least every 4 s while tracking (moving or not); older than this is stale. */
        const val MAX_FIX_AGE_MILLIS = 15_000L
    }
}
