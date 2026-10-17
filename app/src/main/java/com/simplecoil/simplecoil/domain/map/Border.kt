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

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The edge of the playing field (map design: Border): a circle, or a polygon the host tapped out
 * or walked. Distances are worked out on a flat local grid, which is exact to centimetres over a
 * field.
 */
sealed interface Border {
    /** True if [point] is on the field. */
    fun contains(point: LatLon): Boolean

    /** How far [point] is from the edge, in metres, whichever side it is on. */
    fun distanceToEdgeMeters(point: LatLon): Double

    /** A box around the whole border. */
    val bounds: GeoBounds

    /** The edge as a closed ring of points (first = last), for drawing. */
    fun ring(): List<LatLon>

    data class Circle(val center: LatLon, val radiusMeters: Double) : Border {
        init {
            require(radiusMeters > 0) { "radius must be positive" }
        }

        override fun contains(point: LatLon): Boolean = GeoMath.distanceMeters(center, point) <= radiusMeters

        override fun distanceToEdgeMeters(point: LatLon): Double =
            kotlin.math.abs(GeoMath.distanceMeters(center, point) - radiusMeters)

        override val bounds: GeoBounds get() = GeoMath.squareAround(center, radiusMeters * 2)

        override fun ring(): List<LatLon> = (0..CIRCLE_SEGMENTS).map { i ->
            val a = 2 * PI * i / CIRCLE_SEGMENTS
            GeoMath.offset(center, radiusMeters * cos(a), radiusMeters * sin(a))
        }
    }

    /** [corners] in order around the field, not closed (the last joins back to the first). */
    data class Polygon(val corners: List<LatLon>) : Border {
        init {
            require(corners.size >= 3) { "a polygon needs at least 3 corners" }
        }

        private val origin = corners.first()
        private val local = corners.map { GeoMath.toLocal(origin, it) }

        override fun contains(point: LatLon): Boolean {
            val (x, y) = GeoMath.toLocal(origin, point)
            // Ray casting: count edge crossings to the right of the point
            var inside = false
            var j = local.size - 1
            for (i in local.indices) {
                val (xi, yi) = local[i]
                val (xj, yj) = local[j]
                if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
                j = i
            }
            return inside
        }

        override fun distanceToEdgeMeters(point: LatLon): Double {
            val p = GeoMath.toLocal(origin, point)
            var best = Double.MAX_VALUE
            for (i in local.indices) {
                best = minOf(best, segmentDistance(p, local[i], local[(i + 1) % local.size]))
            }
            return best
        }

        override val bounds: GeoBounds
            get() = GeoBounds(corners.maxOf { it.latitude }, corners.minOf { it.latitude },
                corners.maxOf { it.longitude }, corners.minOf { it.longitude })

        override fun ring(): List<LatLon> = corners + corners.first()
    }

    companion object {
        const val CIRCLE_SEGMENTS = 72

        private fun segmentDistance(p: Pair<Double, Double>, a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
            val dx = b.first - a.first
            val dy = b.second - a.second
            val lengthSq = dx * dx + dy * dy
            val t = if (lengthSq == 0.0) 0.0 else (((p.first - a.first) * dx + (p.second - a.second) * dy) / lengthSq).coerceIn(0.0, 1.0)
            return hypot(p.first - (a.first + t * dx), p.second - (a.second + t * dy))
        }
    }
}

/**
 * Drops the points of a walked path that don't change its shape by more than [toleranceMeters]
 * (Douglas-Peucker), so a walk around the field becomes a handful of corners.
 */
object PathSimplifier {
    fun simplify(points: List<LatLon>, toleranceMeters: Double): List<LatLon> {
        if (points.size < 3) return points
        val origin = points.first()
        val local = points.map { GeoMath.toLocal(origin, it) }
        val keep = BooleanArray(points.size)
        keep[0] = true
        keep[points.size - 1] = true
        val stack = ArrayDeque<Pair<Int, Int>>()
        stack.addLast(0 to points.size - 1)
        while (stack.isNotEmpty()) {
            val (start, end) = stack.removeLast()
            var farthest = -1
            var farthestDistance = 0.0
            for (i in start + 1 until end) {
                val d = lineDistance(local[i], local[start], local[end])
                if (d > farthestDistance) {
                    farthestDistance = d
                    farthest = i
                }
            }
            if (farthest >= 0 && farthestDistance > toleranceMeters) {
                keep[farthest] = true
                stack.addLast(start to farthest)
                stack.addLast(farthest to end)
            }
        }
        return points.filterIndexed { i, _ -> keep[i] }
    }

    private fun lineDistance(p: Pair<Double, Double>, a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
        val dx = b.first - a.first
        val dy = b.second - a.second
        val length = hypot(dx, dy)
        if (length == 0.0) return hypot(p.first - a.first, p.second - a.second)
        return kotlin.math.abs(dx * (a.second - p.second) - (a.first - p.first) * dy) / length
    }
}
