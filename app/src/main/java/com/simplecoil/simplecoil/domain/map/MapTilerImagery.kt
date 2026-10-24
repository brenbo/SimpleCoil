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

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * MapTiler's satellite imagery: the US, Europe and the rest of the world, where USGS covers only
 * the US. Used when the build has a MapTiler key (BuildConfig.MAPTILER_KEY, kept out of the
 * source: see app/build.gradle.kts); a build without one uses USGS.
 *
 * The free plan allows 100,000 requests a month, so only the server loads it: the field picker's
 * map, and one photo per field, cut from about 20 of its 512-pixel tiles and shared with the
 * phones.
 */
object MapTilerImagery {
    /** The credit MapTiler asks for wherever its maps are shown. */
    const val CREDIT = "© MapTiler © OpenStreetMap contributors"

    /** The satellite tiles' size: 512 pixels (the TileJSON's "scale": 2). The downloader measures them anyway. */
    const val TILE_PX = 512

    /** The satellite tileset's TileJSON: its tile URL template and zoom range. */
    fun tileJsonUrl(key: String): String = "https://api.maptiler.com/tiles/satellite-v2/tiles.json?key=$key"

    /** One side of the Web Mercator square, in metres. */
    private const val WORLD_M = 2 * PI * GeoMath.MERCATOR_RADIUS_M

    /**
     * A photo is sharp enough once the field's square spans this share of [UsgsImagery.MAX_PIXELS]
     * (0.6 to 0.8 m a pixel); one zoom deeper would cost four times the tiles.
     */
    private const val SHARP_ENOUGH = 0.75

    /** Tiles [x0]..[x1] by [y0]..[y1] at [zoom], [tilePx] pixels each. */
    data class TileRange(val zoom: Int, val x0: Int, val y0: Int, val x1: Int, val y1: Int, val tilePx: Int) {
        val columns: Int get() = x1 - x0 + 1
        val rows: Int get() = y1 - y0 + 1

        /** Where the stitched tiles lie: their outer edges, exactly. */
        val bounds: GeoBounds
            get() {
                val size = WORLD_M / (1 shl zoom)
                return GeoBounds(
                    north = GeoMath.latitudeOf(WORLD_M / 2 - y0 * size),
                    south = GeoMath.latitudeOf(WORLD_M / 2 - (y1 + 1) * size),
                    east = GeoMath.longitudeOf(-WORLD_M / 2 + (x1 + 1) * size),
                    west = GeoMath.longitudeOf(-WORLD_M / 2 + x0 * size),
                )
            }

        /** Every tile, row by row from the top left. */
        fun tiles(): List<Pair<Int, Int>> = (y0..y1).flatMap { y -> (x0..x1).map { x -> x to y } }
    }

    /**
     * A field's photo: the [tiles] stitched, the square [cropLeft], [cropTop], [cropPx] on a side
     * (in the stitched pixels) cut out, and scaled to [sizePx] on a side. It lies over [bounds].
     */
    data class FieldPhoto(val tiles: TileRange, val cropLeft: Int, val cropTop: Int, val cropPx: Int, val sizePx: Int, val bounds: GeoBounds)

    /**
     * The photo of a square [sideMeters] across around [center], square in Web Mercator (which the
     * map draws in) like USGS's: at the shallowest zoom, no deeper than [maxZoom], whose pixels
     * are sharp enough, shrunk to [maxPixels] on a side if it's bigger.
     */
    fun fieldPhoto(center: LatLon, sideMeters: Double, tilePx: Int, maxZoom: Int, maxPixels: Int = UsgsImagery.MAX_PIXELS): FieldPhoto {
        require(sideMeters > 0) { "side must be positive" }
        // Web Mercator stretches distances by 1/cos(latitude)
        val half = sideMeters / 2 / cos(Math.toRadians(center.latitude))
        val cx = GeoMath.mercatorX(center.longitude)
        val cy = GeoMath.mercatorY(center.latitude)
        var zoom = 0
        while (zoom < maxZoom && squarePx(2 * half, zoom, tilePx) < maxPixels * SHARP_ENOUGH) zoom++
        val worldPx = (1 shl zoom).toDouble() * tilePx
        fun px(mercatorX: Double) = (mercatorX + WORLD_M / 2) / WORLD_M * worldPx
        fun py(mercatorY: Double) = (WORLD_M / 2 - mercatorY) / WORLD_M * worldPx
        val left = px(cx - half)
        val top = py(cy + half)
        val cropPx = (2 * half / WORLD_M * worldPx).roundToInt().coerceAtLeast(1)
        val count = 1 shl zoom
        fun tile(p: Double) = floor(p / tilePx).toInt().coerceIn(0, count - 1)
        val range = TileRange(zoom, tile(left), tile(top), tile(left + cropPx - 1), tile(top + cropPx - 1), tilePx)
        val bounds = GeoBounds(
            north = GeoMath.latitudeOf(cy + half),
            south = GeoMath.latitudeOf(cy - half),
            east = GeoMath.longitudeOf(cx + half),
            west = GeoMath.longitudeOf(cx - half),
        )
        return FieldPhoto(range, left.roundToInt() - range.x0 * tilePx, top.roundToInt() - range.y0 * tilePx,
            cropPx, min(cropPx, maxPixels), bounds)
    }

    /** How many pixels [mercatorMeters] spans at [zoom]. */
    private fun squarePx(mercatorMeters: Double, zoom: Int, tilePx: Int): Double = mercatorMeters / WORLD_M * (1 shl zoom) * tilePx

    /** A TileJSON tile [template] for one tile. */
    fun tileUrl(template: String, zoom: Int, x: Int, y: Int): String =
        template.replace("{z}", zoom.toString()).replace("{x}", x.toString()).replace("{y}", y.toString())

    /** The deepest zoom worth asking for: the tileset's own limit, and no deeper than 19. */
    fun usableMaxZoom(tileJsonMaxZoom: Int?): Int = min(tileJsonMaxZoom ?: DEFAULT_MAX_ZOOM, DEFAULT_MAX_ZOOM)

    private const val DEFAULT_MAX_ZOOM = 19
}
