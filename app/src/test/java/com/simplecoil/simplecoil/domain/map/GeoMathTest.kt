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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoMathTest {

    private val field = LatLon(39.0, -77.0)

    @Test
    fun mercatorRoundTrips() {
        for (lat in listOf(-60.0, 0.0, 25.5, 39.0, 64.8)) {
            assertEquals(lat, GeoMath.latitudeOf(GeoMath.mercatorY(lat)), 1e-9)
        }
        for (lon in listOf(-179.0, -77.0, 0.0, 120.25)) {
            assertEquals(lon, GeoMath.longitudeOf(GeoMath.mercatorX(lon)), 1e-9)
        }
    }

    @Test
    fun mercatorMatchesKnownValues() {
        // EPSG:3857 for 39°N 77°W, as the USGS export test used
        assertEquals(-8571600.8, GeoMath.mercatorX(-77.0), 0.5)
        assertEquals(4721671.6, GeoMath.mercatorY(39.0), 0.5)
    }

    @Test
    fun oneDegreeOfLatitudeIsAbout111Km() {
        assertEquals(111_195.0, GeoMath.distanceMeters(LatLon(39.0, -77.0), LatLon(40.0, -77.0)), 5.0)
    }

    @Test
    fun offsetMovesTheGivenDistance() {
        val moved = GeoMath.offset(field, 100.0, -50.0)
        assertEquals(100.0, GeoMath.distanceMeters(field, LatLon(moved.latitude, field.longitude)), 0.1)
        assertEquals(50.0, GeoMath.distanceMeters(field, LatLon(field.latitude, moved.longitude)), 0.1)
        assertTrue(moved.longitude < field.longitude)
    }

    @Test
    fun squareAroundIsTheRightSize() {
        val square = GeoMath.squareAround(field, 500.0)
        val mid = square.center
        assertEquals(field.latitude, mid.latitude, 1e-9)
        assertEquals(field.longitude, mid.longitude, 1e-9)
        assertEquals(500.0, GeoMath.distanceMeters(LatLon(square.south, mid.longitude), LatLon(square.north, mid.longitude)), 0.5)
        assertEquals(500.0, GeoMath.distanceMeters(LatLon(mid.latitude, square.west), LatLon(mid.latitude, square.east)), 0.5)
    }

    @Test
    fun boundsContainsAndExpands() {
        val square = GeoMath.squareAround(field, 200.0)
        assertTrue(square.contains(field))
        val outside = GeoMath.offset(field, 150.0, 0.0)
        assertFalse(square.contains(outside))
        assertTrue(square.expandedBy(60.0).contains(outside))
    }

    @Test
    fun enclosingCircleCoversEveryPoint() {
        val points = listOf(field, GeoMath.offset(field, 120.0, 30.0), GeoMath.offset(field, -40.0, 200.0))
        val circle = GeoMath.enclosingCircle(points)!!
        for (p in points) {
            assertTrue(GeoMath.distanceMeters(circle.center, p) <= circle.radiusMeters + 1e-6)
        }
        assertNull(GeoMath.enclosingCircle(emptyList()))
        assertEquals(0.0, GeoMath.enclosingCircle(listOf(field))!!.radiusMeters, 1e-9)
    }

    @Test
    fun zoomToFitPutsTheCircleAcrossTheView() {
        val circle = GeoCircle(field, 250.0)
        val viewDp = 360.0
        val zoom = GeoMath.zoomToFit(circle, viewDp)
        val diameterDp = circle.radiusMeters * 2 / GeoMath.metersPerPixel(field.latitude, zoom)
        assertEquals(viewDp, diameterDp, 0.5)
    }

    @Test
    fun zoomToFitClamps() {
        assertEquals(20.0, GeoMath.zoomToFit(GeoCircle(field, 0.5), 360.0), 1e-9)
        assertEquals(2.0, GeoMath.zoomToFit(GeoCircle(field, 5_000_000.0), 360.0), 1e-9)
    }

    @Test
    fun aBiggerFieldZoomsOut() {
        val small = GeoMath.zoomToFit(GeoCircle(field, 100.0), 360.0)
        val big = GeoMath.zoomToFit(GeoCircle(field, 800.0), 360.0)
        // Eight times wider is three zoom levels out
        assertEquals(3.0, small - big, 1e-9)
    }
}
