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

import kotlin.math.max

/**
 * How much aerial photo a field needs: enough that no auto zoom view of it shows the photo's
 * edge, on any phone, with the map turned any way. No more, so the photo is as sharp as it can
 * be and costs few map tiles.
 *
 * The widest view, [AutoZoom.FIELD], fits the border's circle across the screen's short side; the
 * screen's corners show more, out to its half-diagonal, and turning the map (heading up) can
 * point a corner anywhere. Following you shows [AutoZoom.FOLLOW_RADIUS_METERS] around you the same
 * way, so the photo also reaches that far past the border.
 */
object FieldPhotoExtent {
    /** A screen's diagonal over its short side: a 21:9 phone, the tallest there is. */
    const val SCREEN_DIAGONAL_PER_SHORT_SIDE = 2.6

    /** Never smaller than this across (a tiny field still shows its surroundings). */
    const val MIN_SIDE_METERS = 300.0

    /** Never bigger than this across: a very big field gets a coarser photo instead. */
    const val MAX_SIDE_METERS = 5_000.0

    /** The photo's middle: the middle of the border's circle, where the field view centres. */
    fun center(border: Border): LatLon = GeoMath.enclosingCircle(border.ring())!!.center

    /** How far across the square photo around [center] must be for [border]. */
    fun sideMeters(border: Border): Double {
        val field = GeoMath.enclosingCircle(border.ring())!!
        val fitted = AutoZoom.target(AutoZoom.FIELD, border, null, null, emptyList())!!
        val fieldView = fitted.radiusMeters * SCREEN_DIAGONAL_PER_SHORT_SIDE
        val followAtTheEdge = field.radiusMeters + AutoZoom.FOLLOW_RADIUS_METERS * SCREEN_DIAGONAL_PER_SHORT_SIDE
        return (2 * max(fieldView, followAtTheEdge)).coerceIn(MIN_SIDE_METERS, MAX_SIDE_METERS)
    }
}
