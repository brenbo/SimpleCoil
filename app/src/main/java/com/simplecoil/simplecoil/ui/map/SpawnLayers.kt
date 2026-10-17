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

import android.content.Context
import com.simplecoil.simplecoil.domain.map.GeoMath
import com.simplecoil.simplecoil.domain.map.Spawn
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.maps.Style
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import kotlin.math.pow

/**
 * Spawns on a map (map design: Respawn points): a soft disc the spawn's real size, and a badge in
 * the team's colour on top. Both are icons, like the player markers, so they draw on every phone
 * (shaded areas didn't on some older ones). Spawns that are moving sit in their own layers, so a
 * fade is just each layer's opacity.
 */
object SpawnLayers {
    private const val ZONE = "zone"
    private const val BADGE = "badge"
    private const val STEADY = "spawns-steady"
    private const val INCOMING = "spawns-in"
    private const val OUTGOING = "spawns-out"
    private val GROUPS = listOf(STEADY, INCOMING, OUTGOING)

    private fun zoneImage(team: Int) = "spawn-zone-$team"
    private fun badgeImage(team: Int) = "spawn-badge-$team"

    /** Adds the spawn images, sources and layers to [style], below whatever is added after. */
    fun install(style: Style, context: Context) {
        for (team in 0..4) {
            val color = if (team == Spawn.ANYONE) MapIcons.ANYONE else MapIcons.teamColor(team)
            style.addImage(zoneImage(team), MapIcons.spawnZone(context, color))
            style.addImage(badgeImage(team), MapIcons.spawnBadge(context, color))
        }
        // Every zone under every badge, so a disc never covers another spawn's badge
        for (group in GROUPS) {
            style.addSource(GeoJsonSource(group))
            style.addLayer(SymbolLayer("$group-zone", group).withProperties(
                PropertyFactory.iconImage(Expression.get(ZONE)),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true),
                PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                PropertyFactory.iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_MAP),
            ))
        }
        for (group in GROUPS) {
            style.addLayer(SymbolLayer("$group-badge", group).withProperties(
                PropertyFactory.iconImage(Expression.get(BADGE)),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true),
            ))
        }
    }

    /**
     * Shows [steady] spawns fully, [incoming] at [progress] and [outgoing] at 1 - [progress];
     * each [radiusMeters] across its middle at [latitude].
     */
    fun update(
        style: Style,
        steady: List<Spawn>,
        incoming: List<Spawn> = emptyList(),
        outgoing: List<Spawn> = emptyList(),
        progress: Double = 1.0,
        radiusMeters: Double,
        latitude: Double,
    ) {
        val zoneSize = zoneSize(radiusMeters, latitude)
        for ((group, spawns, opacity) in listOf(
            Triple(STEADY, steady, 1.0), Triple(INCOMING, incoming, progress), Triple(OUTGOING, outgoing, 1 - progress))) {
            style.getSourceAs<GeoJsonSource>(group)?.setGeoJson(FeatureCollection.fromFeatures(spawns.map { spawn ->
                Feature.fromGeometry(Point.fromLngLat(spawn.point.longitude, spawn.point.latitude)).also {
                    it.addStringProperty(ZONE, zoneImage(spawn.team.coerceIn(0, 4)))
                    it.addStringProperty(BADGE, badgeImage(spawn.team.coerceIn(0, 4)))
                }
            }))
            val alpha = opacity.toFloat().coerceIn(0f, 1f)
            style.getLayer("$group-zone")?.setProperties(PropertyFactory.iconSize(zoneSize), PropertyFactory.iconOpacity(alpha))
            style.getLayer("$group-badge")?.setProperties(PropertyFactory.iconOpacity(alpha))
        }
    }

    /**
     * The zone icon's size at each zoom, so it covers exactly [radiusMeters] around the spawn:
     * the ground under a map pixel halves with each zoom level, so the size doubles.
     */
    private fun zoneSize(radiusMeters: Double, latitude: Double): Expression {
        val atLow = (2 * radiusMeters / GeoMath.metersPerPixel(latitude, LOW_ZOOM) / MapIcons.ZONE_DP).toFloat()
        val atHigh = atLow * 2.0.pow(HIGH_ZOOM - LOW_ZOOM).toFloat()
        return Expression.interpolate(Expression.exponential(2f), Expression.zoom(),
            Expression.stop(LOW_ZOOM.toFloat(), atLow), Expression.stop(HIGH_ZOOM.toFloat(), atHigh))
    }

    private const val LOW_ZOOM = 10.0
    private const val HIGH_ZOOM = 22.0
}
