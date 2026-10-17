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
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** A point on the ground, in degrees. */
data class LatLon(val latitude: Double, val longitude: Double)

/** A latitude/longitude box, in degrees. */
data class GeoBounds(val north: Double, val south: Double, val east: Double, val west: Double) {
    init {
        require(north >= south) { "north < south" }
        require(east >= west) { "east < west" }
    }

    val center: LatLon get() = LatLon((north + south) / 2, (east + west) / 2)

    val corners: List<LatLon>
        get() = listOf(LatLon(north, west), LatLon(north, east), LatLon(south, east), LatLon(south, west))

    fun contains(point: LatLon): Boolean =
        point.latitude in south..north && point.longitude in west..east

    /** This box grown by [meters] on every side. */
    fun expandedBy(meters: Double): GeoBounds {
        val sw = GeoMath.offset(LatLon(south, west), -meters, -meters)
        val ne = GeoMath.offset(LatLon(north, east), meters, meters)
        return GeoBounds(ne.latitude, sw.latitude, ne.longitude, sw.longitude)
    }
}

/** A circle on the ground: [radiusMeters] around [center]. */
data class GeoCircle(val center: LatLon, val radiusMeters: Double)

/**
 * Ground distances and Web Mercator, the projection the map and the USGS imagery use. Good to
 * well under a metre over a playing field; not meant for continent-sized distances.
 */
object GeoMath {
    /** The Web Mercator sphere (EPSG:3857). */
    const val MERCATOR_RADIUS_M = 6378137.0

    /** Mean Earth radius, for ground distances. */
    const val EARTH_RADIUS_M = 6371008.8

    /** Web Mercator's latitude limit. */
    const val MAX_MERCATOR_LATITUDE = 85.05112878

    private const val METERS_PER_DEGREE_LAT = EARTH_RADIUS_M * PI / 180

    /** The map's tile size in density-independent pixels: the world is 512 · 2^zoom across. */
    const val MAP_TILE_SIZE_DP = 512.0

    fun mercatorX(longitude: Double): Double = Math.toRadians(longitude) * MERCATOR_RADIUS_M

    fun mercatorY(latitude: Double): Double {
        val lat = latitude.coerceIn(-MAX_MERCATOR_LATITUDE, MAX_MERCATOR_LATITUDE)
        return ln(tan(PI / 4 + Math.toRadians(lat) / 2)) * MERCATOR_RADIUS_M
    }

    fun longitudeOf(mercatorX: Double): Double = Math.toDegrees(mercatorX / MERCATOR_RADIUS_M)

    fun latitudeOf(mercatorY: Double): Double = Math.toDegrees(2 * atan(exp(mercatorY / MERCATOR_RADIUS_M)) - PI / 2)

    /** Great-circle distance in metres (haversine). */
    fun distanceMeters(a: LatLon, b: LatLon): Double {
        val dLat = Math.toRadians(b.latitude - a.latitude)
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(a.latitude)) * cos(Math.toRadians(b.latitude)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_RADIUS_M * asin(min(1.0, sqrt(h)))
    }

    /** [point] moved [northMeters] north and [eastMeters] east. */
    fun offset(point: LatLon, northMeters: Double, eastMeters: Double): LatLon {
        val lat = point.latitude + northMeters / METERS_PER_DEGREE_LAT
        val lon = point.longitude + eastMeters / (METERS_PER_DEGREE_LAT * cos(Math.toRadians(point.latitude)))
        return LatLon(lat, lon)
    }

    /** [point] on a flat grid around [origin]: metres east (first) and north (second). */
    fun toLocal(origin: LatLon, point: LatLon): Pair<Double, Double> = Pair(
        (point.longitude - origin.longitude) * METERS_PER_DEGREE_LAT * cos(Math.toRadians(origin.latitude)),
        (point.latitude - origin.latitude) * METERS_PER_DEGREE_LAT,
    )

    /** A square [sideMeters] on a side, centred on [center]. */
    fun squareAround(center: LatLon, sideMeters: Double): GeoBounds {
        val half = sideMeters / 2
        val sw = offset(center, -half, -half)
        val ne = offset(center, half, half)
        return GeoBounds(ne.latitude, sw.latitude, ne.longitude, sw.longitude)
    }

    /**
     * A circle around every point in [points]: centred on their bounding box, as big as the
     * farthest point. Not always the smallest such circle, but never more than about 15% bigger.
     * Null for no points.
     */
    fun enclosingCircle(points: List<LatLon>): GeoCircle? {
        if (points.isEmpty()) return null
        val north = points.maxOf { it.latitude }
        val south = points.minOf { it.latitude }
        val east = points.maxOf { it.longitude }
        val west = points.minOf { it.longitude }
        val center = LatLon((north + south) / 2, (east + west) / 2)
        return GeoCircle(center, points.maxOf { distanceMeters(center, it) })
    }

    /** Metres of ground under one map pixel (dp) at [zoom] and [latitude]. */
    fun metersPerPixel(latitude: Double, zoom: Double): Double =
        2 * PI * MERCATOR_RADIUS_M * cos(Math.toRadians(latitude)) / (MAP_TILE_SIZE_DP * Math.pow(2.0, zoom))

    /**
     * The map zoom at which [circle] fits a view [viewSizeDp] across (its narrower side), so it
     * still fits whichever way the map is rotated. Clamped to [minZoom]..[maxZoom].
     */
    fun zoomToFit(circle: GeoCircle, viewSizeDp: Double, minZoom: Double = 2.0, maxZoom: Double = 20.0): Double {
        val diameter = max(circle.radiusMeters * 2, 1.0)
        val worldMeters = 2 * PI * MERCATOR_RADIUS_M * cos(Math.toRadians(circle.center.latitude))
        val zoom = log2(worldMeters * viewSizeDp / (MAP_TILE_SIZE_DP * diameter))
        return zoom.coerceIn(minZoom, maxZoom)
    }
}
