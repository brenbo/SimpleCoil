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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class FieldPhotoExtentTest {

    private val home = LatLon(39.017, -94.37)

    /** What the field view shows on a [width] x [height] screen turned any way: out to the half-diagonal. */
    private fun seenRadius(border: Border, width: Double, height: Double): Double {
        val fitted = AutoZoom.target(AutoZoom.FIELD, border, null, null, emptyList())!!
        val metersPerUnit = 2 * fitted.radiusMeters / minOf(width, height)
        return sqrt(width * width + height * height) / 2 * metersPerUnit
    }

    @Test
    fun theFieldView_neverReachesThePhotosEdge_onAnyPhone_turnedAnyWay() {
        val borders = listOf(
            Border.Circle(home, 100.0),
            Border.Polygon(listOf(GeoMath.offset(home, 60.0, -150.0), GeoMath.offset(home, 60.0, 150.0),
                GeoMath.offset(home, -60.0, 150.0), GeoMath.offset(home, -60.0, -150.0))),
        )
        for (border in borders) {
            val half = FieldPhotoExtent.sideMeters(border) / 2
            // A tall 21:9 phone, a 16:9 one, and a 4:3 tablet
            for ((w, h) in listOf(1080.0 to 2520.0, 1080.0 to 1920.0, 1536.0 to 2048.0)) {
                // A circle of the seen radius around the centre fits inside the square photo
                assertTrue("$border $w x $h", seenRadius(border, w, h) <= half + 1e-6)
            }
        }
    }

    @Test
    fun followingYouAtTheBorder_staysOnThePhoto() {
        val border = Border.Circle(home, 50.0)
        val half = FieldPhotoExtent.sideMeters(border) / 2
        assertTrue(half >= 50.0 + AutoZoom.FOLLOW_RADIUS_METERS * FieldPhotoExtent.SCREEN_DIAGONAL_PER_SHORT_SIDE)
    }

    @Test
    fun aSmallFieldGetsASmallPhoto_farLessThanAMile_andSizesAreKeptSensible() {
        // A 100 m field: about 600 m of photo, sharper and fewer tiles than the old 1600
        assertEquals(598.0, FieldPhotoExtent.sideMeters(Border.Circle(home, 100.0)), 1.0)
        assertEquals(FieldPhotoExtent.MIN_SIDE_METERS, FieldPhotoExtent.sideMeters(Border.Circle(home, 10.0)), 0.0)
        assertEquals(FieldPhotoExtent.MAX_SIDE_METERS, FieldPhotoExtent.sideMeters(Border.Circle(home, 2_000.0)), 0.0)
    }

    @Test
    fun thePhotoCentresWhereTheFieldViewDoes() {
        val border = Border.Polygon(listOf(home, GeoMath.offset(home, 100.0, 0.0), GeoMath.offset(home, 100.0, 200.0)))
        val fitted = AutoZoom.target(AutoZoom.FIELD, border, null, null, emptyList())!!
        assertEquals(fitted.center, FieldPhotoExtent.center(border))
    }
}
