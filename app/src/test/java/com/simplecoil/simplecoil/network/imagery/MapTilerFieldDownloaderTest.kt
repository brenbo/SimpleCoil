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
import com.simplecoil.simplecoil.domain.map.UsgsImagery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class MapTilerFieldDownloaderTest {

    /** Somewhere in Germany: past USGS's reach. */
    private val center = LatLon(50.11, 8.68)
    private val tileJson = """{"tiles":["https://api.maptiler.com/tiles/satellite-v2/{z}/{x}/{y}.jpg?key=K"],"maxzoom":20}"""
    private val asked = ArrayList<String>()

    /** Tiles of [tilePx] pixels; [stitched] records what was stitched. */
    private class FakeStitcher(val tilePx: Int) : MapTilerFieldDownloader.TileStitcher {
        var stitched: MapTilerImagery.FieldPhoto? = null
        override fun widthOf(tile: ByteArray) = tilePx
        override fun stitch(photo: MapTilerImagery.FieldPhoto, tiles: List<ByteArray?>): ByteArray {
            assertEquals(photo.tiles.columns * photo.tiles.rows, tiles.size)
            stitched = photo
            return FieldPacks.jpeg(size = 1000)
        }
    }

    private fun downloader(stitcher: MapTilerFieldDownloader.TileStitcher = FakeStitcher(256), tile: (String) -> FieldDownloader.Response) =
        MapTilerFieldDownloader("K", stitcher, clock = { 99L }) { url ->
            asked += url
            if (url.contains("tiles.json")) FieldDownloader.Response(200, "application/json", tileJson.toByteArray()) else tile(url)
        }

    private fun ok() = FieldDownloader.Response(200, "image/jpeg", FieldPacks.jpeg(size = 5000))

    private fun expectFailure(reason: FieldDownloader.Reason, tile: (String) -> FieldDownloader.Response) {
        try {
            downloader(tile = tile).download(center, UsgsImagery.FIELD_SIDE_METERS)
            fail("expected $reason")
        } catch (e: FieldDownloader.DownloadException) {
            assertEquals(reason, e.reason)
        }
    }

    @Test
    fun theTilesAroundTheField_stitchIntoOnePack_thePhonesCanDraw() {
        val stitcher = FakeStitcher(256)
        val pack = downloader(stitcher) { ok() }.download(center, UsgsImagery.FIELD_SIDE_METERS)
        val photo = stitcher.stitched!!
        val columns = photo.tiles.columns
        val rows = photo.tiles.rows
        assertEquals(256, photo.tiles.tilePx)
        assertEquals(photo.sizePx, pack.info.widthPx)
        assertEquals(photo.sizePx, pack.info.heightPx)
        assertTrue(pack.info.widthPx <= UsgsImagery.MAX_PIXELS)
        // The same square as a USGS photo would be
        assertEquals(UsgsImagery.exportRequest(center, UsgsImagery.FIELD_SIDE_METERS).bounds.north, pack.info.bounds.north, 1e-9)
        assertEquals(MapTilerImagery.CREDIT, pack.info.credit)
        assertEquals(99L, pack.info.createdAtMillis)
        // The TileJSON, then each tile once: a few dozen requests, with the key
        assertEquals(1 + columns * rows, asked.size)
        assertTrue(asked.size < 100)
        assertTrue(asked.all { it.contains("key=K") })
    }

    @Test
    fun biggerTiles_meanOneZoomLess_forTheSamePhoto() {
        val small = FakeStitcher(256)
        downloader(small) { ok() }.download(center, UsgsImagery.FIELD_SIDE_METERS)
        val big = FakeStitcher(512)
        val pack = downloader(big) { ok() }.download(center, UsgsImagery.FIELD_SIDE_METERS)
        assertEquals(512, big.stitched!!.tiles.tilePx)
        assertEquals(small.stitched!!.tiles.zoom - 1, big.stitched!!.tiles.zoom)
        assertTrue(pack.info.widthPx <= UsgsImagery.MAX_PIXELS)
    }

    @Test
    fun noTilesAnywhere_isNoImagery() = expectFailure(FieldDownloader.Reason.NO_IMAGERY) { FieldDownloader.Response(404, null, ByteArray(0)) }

    @Test
    fun aWrongKeyOrTheMonthUsedUp_isRefused() {
        expectFailure(FieldDownloader.Reason.REFUSED) { FieldDownloader.Response(403, "text/plain", ByteArray(0)) }
        expectFailure(FieldDownloader.Reason.REFUSED) { FieldDownloader.Response(429, "text/plain", ByteArray(0)) }
    }

    @Test
    fun noInternet_orAServerError_isTheNetwork() {
        expectFailure(FieldDownloader.Reason.NETWORK) { throw IOException("Unable to resolve host") }
        expectFailure(FieldDownloader.Reason.NETWORK) { FieldDownloader.Response(503, "text/html", ByteArray(0)) }
    }
}
