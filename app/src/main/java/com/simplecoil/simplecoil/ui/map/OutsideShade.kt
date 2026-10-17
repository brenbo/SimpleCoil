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

import androidx.core.graphics.toColorInt
import com.simplecoil.simplecoil.domain.map.LatLon
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon

/**
 * Greys out everything outside the field's border (map design: Border), so the playing area
 * stands out: one shape, a big box with the field cut out of it. A plain fill with fixed paint,
 * which every phone draws (old ones too).
 */
object OutsideShade {
    private const val SOURCE = "outside-shade"
    private const val LAYER = "outside-shade"

    /** Far enough out to cover anything the map can show around a field. */
    private const val MARGIN_DEGREES = 0.5

    /** Adds the shade; call it before the layers that go over it. */
    fun install(style: Style) {
        style.addSource(GeoJsonSource(SOURCE))
        style.addLayer(FillLayer(LAYER, SOURCE).withProperties(
            PropertyFactory.fillColor("#202020".toColorInt()),
            PropertyFactory.fillOpacity(0.5f),
        ))
    }

    /** Shades outside [ring] (the border, closed); nothing without one. */
    fun update(style: Style, ring: List<LatLon>?) {
        val source = style.getSourceAs<GeoJsonSource>(SOURCE) ?: return
        if (ring == null || ring.size < 4) {
            source.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
            return
        }
        val south = ring.minOf { it.latitude } - MARGIN_DEGREES
        val north = ring.maxOf { it.latitude } + MARGIN_DEGREES
        val west = ring.minOf { it.longitude } - MARGIN_DEGREES
        val east = ring.maxOf { it.longitude } + MARGIN_DEGREES
        val outer = listOf(
            Point.fromLngLat(west, south), Point.fromLngLat(east, south), Point.fromLngLat(east, north),
            Point.fromLngLat(west, north), Point.fromLngLat(west, south),
        )
        val hole = ring.map { Point.fromLngLat(it.longitude, it.latitude) }
        source.setGeoJson(Feature.fromGeometry(Polygon.fromLngLats(listOf(outer, hole))))
    }
}
