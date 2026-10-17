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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoZoomTest {

    private val center = LatLon(39.0, -77.0)
    private val photo = GeoMath.squareAround(center, 1600.0)
    private val border = Border.Circle(center, 150.0)

    @Test
    fun theButtonCyclesOffPlayersField() {
        assertEquals(AutoZoom.PLAYERS, AutoZoom.OFF.next())
        assertEquals(AutoZoom.FIELD, AutoZoom.PLAYERS.next())
        assertEquals(AutoZoom.OFF, AutoZoom.FIELD.next())
    }

    @Test
    fun offFitsNothing() {
        assertNull(AutoZoom.target(AutoZoom.OFF, border, photo, center, emptyList()))
    }

    @Test
    fun fieldFitsTheBorder() {
        val target = AutoZoom.target(AutoZoom.FIELD, border, photo, GeoMath.offset(center, 5_000.0, 0.0), emptyList())!!
        assertTrue(GeoMath.distanceMeters(target.center, center) < 2.0)
        assertEquals(150.0 * (1 + AutoZoom.PADDING), target.radiusMeters, 2.0)
    }

    @Test
    fun fieldFitsThePhotoWithoutABorder() {
        val target = AutoZoom.target(AutoZoom.FIELD, null, photo, null, emptyList())!!
        assertEquals(800.0 * Math.sqrt(2.0) * (1 + AutoZoom.PADDING), target.radiusMeters, 5.0)
    }

    @Test
    fun fieldFallsBackToThePlayers() {
        val a = GeoMath.offset(center, 0.0, -100.0)
        val b = GeoMath.offset(center, 0.0, 100.0)
        val target = AutoZoom.target(AutoZoom.FIELD, null, null, a, listOf(b))!!
        assertEquals(100.0 * (1 + AutoZoom.PADDING), target.radiusMeters, 1.0)
    }

    @Test
    fun playersFitsEveryoneButNotTheField() {
        val a = GeoMath.offset(center, 0.0, -150.0)
        val b = GeoMath.offset(center, 0.0, 150.0)
        val target = AutoZoom.target(AutoZoom.PLAYERS, border, photo, a, listOf(b))!!
        assertEquals(150.0 * (1 + AutoZoom.PADDING), target.radiusMeters, 1.0)
    }

    @Test
    fun aLonePlayerGetsTheMinimumView() {
        val target = AutoZoom.target(AutoZoom.PLAYERS, null, null, center, emptyList())!!
        assertEquals(AutoZoom.MIN_RADIUS_METERS, target.radiusMeters, 1e-9)
    }

    @Test
    fun nothingToFit() {
        assertNull(AutoZoom.target(AutoZoom.PLAYERS, border, photo, null, emptyList()))
        assertNull(AutoZoom.target(AutoZoom.FIELD, null, null, null, emptyList()))
    }
}
