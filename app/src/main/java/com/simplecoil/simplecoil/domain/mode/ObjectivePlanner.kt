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
import kotlin.math.PI
import kotlin.math.abs
import kotlin.random.Random

/**
 * Where capture points go (map design: Domination): how many by the field's size, kept far
 * enough apart that GPS error can't put anyone on two at once, and clear of the spawns.
 */
object ObjectivePlanner {

    /** Points stay at least this far apart, edge to edge, beyond their radius. */
    const val GAP_METERS = 15.0

    private const val CANDIDATES = 400
    private const val RELAX_STEP = 0.7
    private const val RELAX_STEPS = 6

    /** The field's area in square metres. */
    fun areaSquareMeters(border: Border): Double = when (border) {
        is Border.Circle -> PI * border.radiusMeters * border.radiusMeters
        is Border.Polygon -> {
            val origin = border.corners.first()
            val local = border.corners.map { GeoMath.toLocal(origin, it) }
            var twice = 0.0
            for (i in local.indices) {
                val (x1, y1) = local[i]
                val (x2, y2) = local[(i + 1) % local.size]
                twice += x1 * y2 - x2 * y1
            }
            abs(twice) / 2
        }
    }

    /**
     * How many points [mode] gets on [border]: Domination's by the rules' count or the field's size;
     * Hardpoint always moves between a few spots, by the field's size (one is live at a time).
     */
    fun count(mode: GameMode, border: Border, rules: ObjectiveRules): Int {
        if (rules.pointCount > 0 && mode != GameMode.HARDPOINT) return rules.pointCount
        val area = areaSquareMeters(border)
        val bySize = when {
            area < rules.smallFieldSquareMeters -> 1
            area > rules.largeFieldSquareMeters -> 3
            else -> 2
        }
        // Hardpoint moves between a few more spots than Domination holds at once
        return if (mode == GameMode.HARDPOINT) (bySize + 2).coerceIn(3, 5) else bySize
    }

    /**
     * [count] points inside [border], [edgeBufferMeters] plus their radius in from the edge,
     * clear of [spawns] (each [spawnRadiusMeters] in radius) and of each other. The
     * first goes near the middle, the rest as far from the others as they can, lettered A, B, C
     * along the field. Rules loosen step by step on a field too small for them.
     */
    fun place(
        border: Border,
        count: Int,
        rules: ObjectiveRules,
        spawns: List<Spawn>,
        spawnRadiusMeters: Double,
        edgeBufferMeters: Double,
        random: Random = Random.Default,
    ): List<Objective> {
        if (count <= 0) return emptyList()
        val bounds = border.bounds
        val center = bounds.center
        val candidates = (0 until CANDIDATES).map {
            LatLon(bounds.south + random.nextDouble() * (bounds.north - bounds.south),
                bounds.west + random.nextDouble() * (bounds.east - bounds.west))
        }.filter { border.contains(it) }
        if (candidates.isEmpty()) return emptyList()

        var scale = 1.0
        repeat(RELAX_STEPS + 1) {
            tryPlace(border, count, rules, spawns, spawnRadiusMeters, edgeBufferMeters, center, candidates, scale)?.let { return letter(it, spawns, center) }
            scale *= RELAX_STEP
        }
        return letter(tryPlace(border, count, rules, spawns, spawnRadiusMeters, 0.0, center, candidates, 0.0) ?: emptyList(), spawns, center)
    }

    private fun tryPlace(
        border: Border,
        count: Int,
        rules: ObjectiveRules,
        spawns: List<Spawn>,
        spawnRadius: Double,
        edgeBuffer: Double,
        center: LatLon,
        candidates: List<LatLon>,
        scale: Double,
    ): List<LatLon>? {
        val apart = (rules.radiusMeters * 2 + GAP_METERS) * scale
        val spawnClear = (spawnRadius + rules.radiusMeters + GAP_METERS) * scale
        val usable = candidates.filter { spot ->
            border.distanceToEdgeMeters(spot) >= (edgeBuffer + rules.radiusMeters) * scale &&
                spawns.all { GeoMath.distanceMeters(spot, it.point) >= spawnClear }
        }
        val placed = ArrayList<LatLon>()
        repeat(count) {
            val options = usable.filter { spot -> placed.all { GeoMath.distanceMeters(spot, it) >= apart } }
            val best = if (placed.isEmpty() && count % 2 == 1) {
                // An odd count has one in the middle, like Domination's B
                options.minByOrNull { GeoMath.distanceMeters(it, center) }
            } else {
                options.maxByOrNull { spot -> placed.minOfOrNull { GeoMath.distanceMeters(spot, it) } ?: -GeoMath.distanceMeters(spot, center) }
            } ?: return null
            placed += best
        }
        return placed
    }

    /** Letters along the line from the first team's spawn to the second's (or west to east). */
    private fun letter(points: List<LatLon>, spawns: List<Spawn>, center: LatLon): List<Objective> {
        val from = spawns.firstOrNull { it.team == 1 }?.point
        val to = spawns.firstOrNull { it.team == 2 }?.point
        val ordered = if (from != null && to != null) {
            val (axisEast, axisNorth) = GeoMath.toLocal(from, to)
            points.sortedBy { val (e, n) = GeoMath.toLocal(from, it); e * axisEast + n * axisNorth }
        } else {
            points.sortedBy { GeoMath.toLocal(center, it).first }
        }
        return ordered.mapIndexed { index, point -> Objective(Objective.letter(index), point) }
    }
}
