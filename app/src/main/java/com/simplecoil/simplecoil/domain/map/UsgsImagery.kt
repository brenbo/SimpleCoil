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

import java.util.Locale
import kotlin.math.cos
import kotlin.math.roundToInt

/**
 * The USGS National Map imagery the map uses (map design: Map engine). Free and public domain;
 * USGS asks for [CREDIT] wherever it is shown.
 *
 * - [exportRequest]: one aerial photo of a field from the NAIP image service, about 0.5 m a
 *   pixel. The dedicated server downloads it once and shares it with the phones.
 * - [BROWSE_TILES]: map tiles the server's field picker shows while the host looks for the
 *   field. These stop at zoom 16 (about 2 m a pixel); players' phones never load them.
 */
object UsgsImagery {
    const val CREDIT = "Map services and data available from U.S. Geological Survey, National Geospatial Program."

    /** The NAIP aerial photography service (about 0.6 m, resampled). */
    const val EXPORT_URL = "https://imagery.nationalmap.gov/arcgis/rest/services/USGSNAIPImagery/ImageServer/exportImage"

    /** Imagery with roads and place names, for finding a field from home; z/y/x as ArcGIS orders them. */
    const val BROWSE_TILES = "https://basemap.nationalmap.gov/arcgis/rest/services/USGSImageryTopo/MapServer/tile/{z}/{y}/{x}"

    /** The tile services' deepest zoom; the map stretches it beyond. */
    const val TILES_MAX_ZOOM = 16

    /** Ground metres per pixel we ask for: about NAIP's own resolution. */
    const val TARGET_METERS_PER_PIXEL = 0.5

    /**
     * The photo's longest side in pixels. 2048 is the largest texture every phone back to
     * Android 6 can draw; past it a field gets fewer pixels per metre, not a bigger photo.
     */
    const val MAX_PIXELS = 2048
    const val MIN_PIXELS = 256

    /**
     * How much the server downloads, in metres across, before there's a border: about a mile.
     * With a border, just what its views need (FieldPhotoExtent).
     */
    const val FIELD_SIDE_METERS = 1600.0

    /** One photo to download: the square [bounds], [sizePx] pixels on a side, from [url]. */
    data class ExportRequest(val bounds: GeoBounds, val sizePx: Int, val url: String) {
        /** Ground metres per pixel at the photo's centre. */
        val metersPerPixel: Double
            get() = GeoMath.distanceMeters(LatLon(bounds.center.latitude, bounds.west), LatLon(bounds.center.latitude, bounds.east)) / sizePx
    }

    /**
     * The request for a square photo [sideMeters] across, centred on [center]. The square is
     * square in Web Mercator, which the map draws in, so the photo lines up with it exactly.
     */
    fun exportRequest(center: LatLon, sideMeters: Double): ExportRequest {
        require(sideMeters > 0) { "side must be positive" }
        // Web Mercator stretches distances by 1/cos(latitude)
        val half = sideMeters / 2 / cos(Math.toRadians(center.latitude))
        val cx = GeoMath.mercatorX(center.longitude)
        val cy = GeoMath.mercatorY(center.latitude)
        val minX = cx - half
        val maxX = cx + half
        val minY = cy - half
        val maxY = cy + half
        val sizePx = (sideMeters / TARGET_METERS_PER_PIXEL).roundToInt().coerceIn(MIN_PIXELS, MAX_PIXELS)
        val bounds = GeoBounds(
            north = GeoMath.latitudeOf(maxY),
            south = GeoMath.latitudeOf(minY),
            east = GeoMath.longitudeOf(maxX),
            west = GeoMath.longitudeOf(minX),
        )
        val bbox = String.format(Locale.US, "%.2f,%.2f,%.2f,%.2f", minX, minY, maxX, maxY)
        val url = "$EXPORT_URL?bbox=$bbox&bboxSR=3857&imageSR=3857&size=$sizePx,$sizePx&format=jpg&compressionQuality=85&f=image"
        return ExportRequest(bounds, sizePx, url)
    }

    /**
     * True if a downloaded JPEG is so small for its size that it can only be one flat colour:
     * what the service returns where it has no imagery (outside the US, or open water). Real
     * aerial photos run 0.2 to 0.4 bytes a pixel; a blank one under 0.02.
     */
    fun looksBlank(jpegBytes: Int, widthPx: Int, heightPx: Int): Boolean =
        jpegBytes < widthPx.toLong() * heightPx * BLANK_BYTES_PER_PIXEL

    private const val BLANK_BYTES_PER_PIXEL = 0.05
}
