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
import com.simplecoil.simplecoil.domain.map.UsgsImagery
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class UsgsFieldDownloaderTest {

    private val center = LatLon(39.0, -77.0)

    private fun downloader(response: (String) -> FieldDownloader.Response) =
        UsgsFieldDownloader(clock = { 1234L }, fetch = response)

    private fun expectFailure(reason: FieldDownloader.Reason, response: (String) -> FieldDownloader.Response) {
        try {
            downloader(response).download(center, 500.0)
            fail("expected $reason")
        } catch (e: FieldDownloader.DownloadException) {
            assertEquals(reason, e.reason)
        }
    }

    @Test
    fun aPhotoBecomesAPack() {
        var asked: String? = null
        // 0.3 bytes a pixel, like a real 1000-pixel photo
        val photo = FieldPacks.jpeg(size = 300_000)
        val pack = downloader { url -> asked = url; FieldDownloader.Response(200, "image/jpeg", photo) }.download(center, 500.0)

        val request = UsgsImagery.exportRequest(center, 500.0)
        assertEquals(request.url, asked)
        assertEquals(request.bounds, pack.info.bounds)
        assertEquals(1000, pack.info.widthPx)
        assertEquals(1234L, pack.info.createdAtMillis)
        assertEquals(UsgsImagery.CREDIT, pack.info.credit)
        assertArrayEquals(photo, pack.image)
    }

    @Test
    fun noInternet() {
        expectFailure(FieldDownloader.Reason.NETWORK) { throw IOException("Unable to resolve host") }
    }

    @Test
    fun aServerError() {
        expectFailure(FieldDownloader.Reason.NETWORK) { FieldDownloader.Response(503, "text/html", ByteArray(10)) }
    }

    @Test
    fun anErrorPageInsteadOfAPhoto() {
        expectFailure(FieldDownloader.Reason.NETWORK) {
            FieldDownloader.Response(200, "application/json", "{\"error\":{}}".toByteArray())
        }
        // Claims to be an image but isn't a JPEG
        expectFailure(FieldDownloader.Reason.NETWORK) { FieldDownloader.Response(200, "image/jpeg", ByteArray(300_000)) }
    }

    @Test
    fun somewhereWithoutImagery() {
        // What USGS sent for open water: a flat photo of about 0.017 bytes a pixel
        expectFailure(FieldDownloader.Reason.NO_IMAGERY) {
            FieldDownloader.Response(200, "image/jpeg", FieldPacks.jpeg(size = 16_651))
        }
    }
}
