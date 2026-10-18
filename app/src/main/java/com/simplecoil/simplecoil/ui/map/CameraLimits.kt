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

package com.simplecoil.simplecoil.ui.map

import com.simplecoil.simplecoil.domain.map.GeoMath
import com.simplecoil.simplecoil.domain.map.LatLon
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap

/**
 * Keeps a map's camera near the field, so it can't be dragged off sideways forever (the map
 * repeats around the world): the field and [MARGIN_METERS] around it.
 */
object CameraLimits {
    const val MARGIN_METERS = 1_500.0

    /** Limits [map] to around [points] (the border, the photo's corners...); none if there are none. */
    fun keepNear(map: MapLibreMap, points: List<LatLon>) {
        if (points.isEmpty()) {
            map.setLatLngBoundsForCameraTarget(null)
            return
        }
        val south = points.minOf { it.latitude }
        val north = points.maxOf { it.latitude }
        val west = points.minOf { it.longitude }
        val east = points.maxOf { it.longitude }
        val southWest = GeoMath.offset(LatLon(south, west), -MARGIN_METERS, -MARGIN_METERS)
        val northEast = GeoMath.offset(LatLon(north, east), MARGIN_METERS, MARGIN_METERS)
        map.setLatLngBoundsForCameraTarget(LatLngBounds.Builder()
            .include(LatLng(southWest.latitude, southWest.longitude))
            .include(LatLng(northEast.latitude, northEast.longitude))
            .build())
    }
}
