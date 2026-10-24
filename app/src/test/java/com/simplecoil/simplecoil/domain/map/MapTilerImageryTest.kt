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

class MapTilerImageryTest {

    @Test
    fun aTilesBounds_areItsEdges() {
        // Zoom 1: four tiles; the top left one is the north-west quarter of the world
        val nw = MapTilerImagery.TileRange(1, 0, 0, 0, 0, 256).bounds
        assertEquals(-180.0, nw.west, 1e-9)
        assertEquals(0.0, nw.east, 1e-9)
        assertEquals(GeoMath.MAX_MERCATOR_LATITUDE, nw.north, 1e-6)
        assertEquals(0.0, nw.south, 1e-9)
    }

    @Test
    fun theFieldsPhoto_isUsgsSquare_sharp_andCutFromTheTilesUnderIt() {
        // The US, Frankfurt, Helsinki, near the equator
        for (center in listOf(LatLon(39.0, -77.0), LatLon(50.11, 8.68), LatLon(60.17, 24.94), LatLon(0.5, 30.0))) {
            val photo = MapTilerImagery.fieldPhoto(center, 1600.0, 256, maxZoom = 19)
            // The same square as USGS's photo, so a field drawn on one lines up on the other
            val usgs = UsgsImagery.exportRequest(center, 1600.0).bounds
            assertEquals(usgs.north, photo.bounds.north, 1e-9)
            assertEquals(usgs.west, photo.bounds.west, 1e-9)
            assertTrue("$center", photo.sizePx <= 2048)
            // Under a metre a pixel
            assertTrue("$center", 1600.0 / photo.sizePx < 1.0)
            // The cut lies inside the stitched tiles, and the tiles stay few
            val t = photo.tiles
            assertTrue("$center", photo.cropLeft >= 0 && photo.cropTop >= 0)
            assertTrue("$center", photo.cropLeft + photo.cropPx <= t.columns * 256 && photo.cropTop + photo.cropPx <= t.rows * 256)
            // About 60 in the US and western Europe; more far north, where tiles cover less ground
            assertTrue("$center: ${t.columns * t.rows} tiles", t.columns * t.rows <= 150)
        }
    }

    @Test
    fun aTilesetsOwnLimit_isKept() {
        assertEquals(15, MapTilerImagery.fieldPhoto(LatLon(39.0, -77.0), 1600.0, 256, maxZoom = 15).tiles.zoom)
        assertEquals(19, MapTilerImagery.usableMaxZoom(22))
        assertEquals(14, MapTilerImagery.usableMaxZoom(14))
        assertEquals("https://t/17/5/9.jpg?key=K", MapTilerImagery.tileUrl("https://t/{z}/{x}/{y}.jpg?key=K", 17, 5, 9))
    }
}
