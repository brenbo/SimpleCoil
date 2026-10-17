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
import com.simplecoil.simplecoil.domain.map.GeoCircle
import com.simplecoil.simplecoil.domain.map.GeoMath
import com.simplecoil.simplecoil.domain.mode.ZoneState
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Battle Royale's zone on the play map (map design: Battle Royale): everything outside it
 * tinted storm purple, its edge in white, and the next circle dashed. Fixed paint only, so old
 * phones draw it too.
 */
object ZoneLayers {
    private const val STORM_SOURCE = "zone-storm"
    private const val EDGE_SOURCE = "zone-edge"
    private const val NEXT_SOURCE = "zone-next"
    private const val SEGMENTS = 72

    /** How far out the storm reaches: past anything the map shows around a field. */
    private const val STORM_MARGIN_DEGREES = 0.5

    fun install(style: Style) {
        style.addSource(GeoJsonSource(STORM_SOURCE))
        style.addLayer(FillLayer(STORM_SOURCE, STORM_SOURCE).withProperties(
            PropertyFactory.fillColor("#6A1B9A".toColorInt()),
            PropertyFactory.fillOpacity(0.35f),
        ))
        style.addSource(GeoJsonSource(NEXT_SOURCE))
        style.addLayer(LineLayer(NEXT_SOURCE, NEXT_SOURCE).withProperties(
            PropertyFactory.lineColor("#FFFFFF".toColorInt()),
            PropertyFactory.lineWidth(2f),
            PropertyFactory.lineDasharray(arrayOf(2f, 2f)),
        ))
        style.addSource(GeoJsonSource(EDGE_SOURCE))
        style.addLayer(LineLayer(EDGE_SOURCE, EDGE_SOURCE).withProperties(
            PropertyFactory.lineColor("#FFFFFF".toColorInt()),
            PropertyFactory.lineWidth(3f),
        ))
    }

    /** Shows [zone], or takes it all away when null. */
    fun update(style: Style, zone: ZoneState?) {
        val storm = style.getSourceAs<GeoJsonSource>(STORM_SOURCE) ?: return
        val edge = style.getSourceAs<GeoJsonSource>(EDGE_SOURCE) ?: return
        val next = style.getSourceAs<GeoJsonSource>(NEXT_SOURCE) ?: return
        if (zone == null) {
            listOf(storm, edge, next).forEach { it.setGeoJson(FeatureCollection.fromFeatures(emptyList())) }
            return
        }
        val ring = ring(zone.current)
        val c = zone.current.center
        val outer = listOf(
            Point.fromLngLat(c.longitude - STORM_MARGIN_DEGREES, c.latitude - STORM_MARGIN_DEGREES),
            Point.fromLngLat(c.longitude + STORM_MARGIN_DEGREES, c.latitude - STORM_MARGIN_DEGREES),
            Point.fromLngLat(c.longitude + STORM_MARGIN_DEGREES, c.latitude + STORM_MARGIN_DEGREES),
            Point.fromLngLat(c.longitude - STORM_MARGIN_DEGREES, c.latitude + STORM_MARGIN_DEGREES),
            Point.fromLngLat(c.longitude - STORM_MARGIN_DEGREES, c.latitude - STORM_MARGIN_DEGREES),
        )
        storm.setGeoJson(Feature.fromGeometry(Polygon.fromLngLats(listOf(outer, ring))))
        edge.setGeoJson(Feature.fromGeometry(LineString.fromLngLats(ring)))
        next.setGeoJson(FeatureCollection.fromFeatures(listOfNotNull(zone.next?.let { Feature.fromGeometry(LineString.fromLngLats(ring(it))) })))
    }

    private fun ring(circle: GeoCircle): List<Point> = (0..SEGMENTS).map { i ->
        val a = 2 * PI * i / SEGMENTS
        val p = GeoMath.offset(circle.center, circle.radiusMeters * cos(a), circle.radiusMeters * sin(a))
        Point.fromLngLat(p.longitude, p.latitude)
    }
}
