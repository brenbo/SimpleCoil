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
import org.junit.Assert.assertTrue
import org.junit.Test

class UsgsImageryTest {

    private val center = LatLon(39.0, -77.0)

    @Test
    fun aMediumFieldIsAbout500MetresAcrossAtHalfAMetreAPixel() {
        val request = UsgsImagery.exportRequest(center, 500.0)
        val b = request.bounds
        val mid = b.center
        assertEquals(500.0, GeoMath.distanceMeters(LatLon(mid.latitude, b.west), LatLon(mid.latitude, b.east)), 1.0)
        assertEquals(500.0, GeoMath.distanceMeters(LatLon(b.south, mid.longitude), LatLon(b.north, mid.longitude)), 2.0)
        assertEquals(1000, request.sizePx)
        assertEquals(0.5, request.metersPerPixel, 0.01)
    }

    @Test
    fun theSquareIsSquareInMercator() {
        val b = UsgsImagery.exportRequest(center, 1000.0).bounds
        val width = GeoMath.mercatorX(b.east) - GeoMath.mercatorX(b.west)
        val height = GeoMath.mercatorY(b.north) - GeoMath.mercatorY(b.south)
        assertEquals(width, height, 0.01)
    }

    @Test
    fun bigFieldsAreCappedAtTheTextureLimit() {
        val request = UsgsImagery.exportRequest(center, 1600.0)
        assertEquals(UsgsImagery.MAX_PIXELS, request.sizePx)
        assertTrue(request.metersPerPixel > 0.5)
    }

    @Test
    fun tinyFieldsStillGetAUsefulPhoto() {
        assertEquals(UsgsImagery.MIN_PIXELS, UsgsImagery.exportRequest(center, 50.0).sizePx)
    }

    @Test
    fun theUrlAsksForAWebMercatorJpeg() {
        val request = UsgsImagery.exportRequest(center, 500.0)
        val url = request.url
        assertTrue(url.startsWith(UsgsImagery.EXPORT_URL + "?"))
        assertTrue(url.contains("bboxSR=3857"))
        assertTrue(url.contains("imageSR=3857"))
        assertTrue(url.contains("size=1000,1000"))
        assertTrue(url.contains("format=jpg"))
        assertTrue(url.contains("f=image"))
        val bbox = url.substringAfter("bbox=").substringBefore("&").split(",").map { it.toDouble() }
        assertEquals(GeoMath.mercatorX(request.bounds.west), bbox[0], 0.01)
        assertEquals(GeoMath.mercatorY(request.bounds.south), bbox[1], 0.01)
        assertEquals(GeoMath.mercatorX(request.bounds.east), bbox[2], 0.01)
        assertEquals(GeoMath.mercatorY(request.bounds.north), bbox[3], 0.01)
    }

    @Test
    fun theServerAlwaysDownloadsAboutAMile() {
        val request = UsgsImagery.exportRequest(center, UsgsImagery.FIELD_SIDE_METERS)
        assertEquals(UsgsImagery.MAX_PIXELS, request.sizePx)
        // About 0.8 m a pixel: still sharp enough to see paths and cars
        assertTrue(request.metersPerPixel < 0.8)
    }

    @Test
    fun blankPhotosAreRecognised() {
        // Sizes USGS returned for a spot with no imagery, and for a real field (Oct 2026)
        assertTrue(UsgsImagery.looksBlank(4_780, 500, 500))
        assertTrue(UsgsImagery.looksBlank(16_651, 1000, 1000))
        assertTrue(UsgsImagery.looksBlank(66_698, 2048, 2048))
        assertFalse(UsgsImagery.looksBlank(339_897, 1000, 1000))
        assertFalse(UsgsImagery.looksBlank(90_660, 600, 600))
    }
}
