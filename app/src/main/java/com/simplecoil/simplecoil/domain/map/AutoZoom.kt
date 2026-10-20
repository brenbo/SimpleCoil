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

/**
 * The map's auto zoom (map design: Auto zoom), picked from the map button's menu: off (the player moves the
 * map), follow you up close, fit everyone the map shows, or fit the whole field. It fits a
 * circle rather than a box, so what it fits stays on screen however the map is rotated.
 */
enum class AutoZoom {
    /** The player pans and zooms; only the heading turns the map. */
    OFF,

    /** Close in on you, the map following as you move. */
    YOU,

    /** Everyone and everything on the map: you, the players shown, and later objectives and drops. */
    PLAYERS,

    /** The whole field: its border, or its photo when there's no border. */
    FIELD;

    companion object {
        /** Never zoom in closer than a circle this big, so two players standing together aren't a blur. */
        const val MIN_RADIUS_METERS = 40.0

        /** Room around what is fitted, as a share of its radius. */
        const val PADDING = 0.15

        /** How much of the field around you the follow mode shows: about this far to the screen's edge. */
        const val FOLLOW_RADIUS_METERS = 25.0

        /**
         * The circle [mode] fits, or null when it has nothing to fit (or is [OFF]). [FIELD] falls
         * back to the players when there's no field; [PLAYERS] uses only the people.
         */
        fun target(mode: AutoZoom, border: Border?, photo: GeoBounds?, you: LatLon?, others: List<LatLon>): GeoCircle? {
            val points = when (mode) {
                OFF -> return null
                YOU -> return you?.let { GeoCircle(it, FOLLOW_RADIUS_METERS) }
                FIELD -> border?.ring() ?: photo?.corners ?: (listOfNotNull(you) + others)
                PLAYERS -> listOfNotNull(you) + others
            }
            val circle = GeoMath.enclosingCircle(points) ?: return null
            return GeoCircle(circle.center, maxOf(circle.radiusMeters * (1 + PADDING), MIN_RADIUS_METERS))
        }
    }
}
