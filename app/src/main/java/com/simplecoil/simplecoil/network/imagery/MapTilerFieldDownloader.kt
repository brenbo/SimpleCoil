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

package com.simplecoil.simplecoil.network.imagery

import com.simplecoil.simplecoil.domain.map.LatLon
import com.simplecoil.simplecoil.domain.map.MapTilerImagery
import com.simplecoil.simplecoil.network.imagery.FieldDownloader.DownloadException
import com.simplecoil.simplecoil.network.imagery.FieldDownloader.Reason
import com.simplecoil.simplecoil.network.imagery.FieldDownloader.Response
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection

/**
 * Downloads a field's aerial photo from MapTiler's satellite tiles: the tileset's TileJSON (its
 * tile URL and deepest zoom), then the tiles under the field's square at a zoom sharp enough,
 * stitched, cut to the square and shrunk to fit by [stitcher]. 50 to 80 requests a field.
 */
class MapTilerFieldDownloader(
    private val key: String,
    private val stitcher: TileStitcher,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Opens the request; replaced in tests. */
    private val fetch: (String) -> Response = FieldDownloader::httpGet,
) : FieldDownloader {

    /** Decodes and stitches the tiles (Android's Bitmap on a phone; a fake in tests). */
    interface TileStitcher {
        /** One tile's width in pixels, or null if it isn't an image. */
        fun widthOf(tile: ByteArray): Int?

        /**
         * [photo]'s tiles ([tiles], row by row from the top left, null for a missing one) stitched,
         * its square cut out and scaled to its size, as one JPEG.
         */
        fun stitch(photo: MapTilerImagery.FieldPhoto, tiles: List<ByteArray?>): ByteArray
    }

    @Throws(DownloadException::class)
    override fun download(center: LatLon, sideMeters: Double): FieldPack {
        val (template, maxZoom) = tileJson()
        // Tiles are usually 256 pixels; the first one says what they really are
        var photo = MapTilerImagery.fieldPhoto(center, sideMeters, ASSUMED_TILE_PX, maxZoom)
        var first = tile(template, photo.tiles.zoom, photo.tiles.x0, photo.tiles.y0)
        if (first != null) {
            val px = stitcher.widthOf(first) ?: throw DownloadException(Reason.NETWORK, "MapTiler sent a tile that isn't an image")
            if (px != photo.tiles.tilePx) {
                photo = MapTilerImagery.fieldPhoto(center, sideMeters, px, maxZoom)
                first = tile(template, photo.tiles.zoom, photo.tiles.x0, photo.tiles.y0)
            }
        }
        val range = photo.tiles
        val tiles = range.tiles().map { (x, y) -> if (x == range.x0 && y == range.y0) first else tile(template, range.zoom, x, y) }
        if (tiles.all { it == null }) throw DownloadException(Reason.NO_IMAGERY, "no imagery here")
        val jpeg = stitcher.stitch(photo, tiles)
        return FieldPack.create(photo.bounds, photo.sizePx, photo.sizePx, jpeg, MapTilerImagery.CREDIT, clock())
    }

    /** The tileset's tile URL template and deepest zoom. */
    private fun tileJson(): Pair<String, Int> {
        val response = get(MapTilerImagery.tileJsonUrl(key))
        if (response.code != HttpURLConnection.HTTP_OK) failed(response.code)
        return try {
            val json = JSONObject(String(response.body, Charsets.UTF_8))
            val template = json.getJSONArray("tiles").getString(0)
            template to MapTilerImagery.usableMaxZoom(if (json.has("maxzoom")) json.getInt("maxzoom") else null)
        } catch (e: JSONException) {
            throw DownloadException(Reason.NETWORK, "MapTiler sent a TileJSON we can't read", e)
        }
    }

    /** One tile, or null where there's none (outside the imagery). */
    private fun tile(template: String, zoom: Int, x: Int, y: Int): ByteArray? {
        val response = get(MapTilerImagery.tileUrl(template, zoom, x, y))
        return when (response.code) {
            HttpURLConnection.HTTP_OK -> response.body.takeIf { it.isNotEmpty() }
            HttpURLConnection.HTTP_NO_CONTENT, HttpURLConnection.HTTP_NOT_FOUND -> null
            else -> failed(response.code)
        }
    }

    private fun get(url: String): Response = try {
        fetch(url)
    } catch (e: IOException) {
        throw DownloadException(Reason.NETWORK, "couldn't reach MapTiler: " + e.message, e)
    }

    private fun failed(code: Int): Nothing = when (code) {
        // A wrong key, or the month's free requests used up (MapTiler pauses until the next month)
        HttpURLConnection.HTTP_UNAUTHORIZED, HttpURLConnection.HTTP_FORBIDDEN, TOO_MANY_REQUESTS ->
            throw DownloadException(Reason.REFUSED, "MapTiler refused: $code")
        else -> throw DownloadException(Reason.NETWORK, "MapTiler answered $code")
    }

    private companion object {
        const val ASSUMED_TILE_PX = 256
        const val TOO_MANY_REQUESTS = 429
    }
}
