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
import com.simplecoil.simplecoil.network.imagery.FieldDownloader.DownloadException
import com.simplecoil.simplecoil.network.imagery.FieldDownloader.Reason
import com.simplecoil.simplecoil.network.imagery.FieldDownloader.Response
import java.io.IOException
import java.net.HttpURLConnection

/** Downloads a field's aerial photo from USGS, in one request: the US only. */
class UsgsFieldDownloader(
    private val clock: () -> Long = System::currentTimeMillis,
    /** Opens the request; replaced in tests. */
    private val fetch: (String) -> Response = FieldDownloader::httpGet,
) : FieldDownloader {

    @Throws(DownloadException::class)
    override fun download(center: LatLon, sideMeters: Double): FieldPack {
        val request = UsgsImagery.exportRequest(center, sideMeters)
        val response = try {
            fetch(request.url)
        } catch (e: IOException) {
            throw DownloadException(Reason.NETWORK, "couldn't reach USGS: " + e.message, e)
        }
        if (response.code != HttpURLConnection.HTTP_OK) {
            throw DownloadException(Reason.NETWORK, "USGS answered ${response.code}")
        }
        if (response.contentType?.startsWith("image/") != true || !FieldDownloader.isJpeg(response.body)) {
            throw DownloadException(Reason.NETWORK, "USGS sent ${response.contentType}, not a photo")
        }
        if (UsgsImagery.looksBlank(response.body.size, request.sizePx, request.sizePx)) {
            throw DownloadException(Reason.NO_IMAGERY, "no imagery here")
        }
        return FieldPack.create(request.bounds, request.sizePx, request.sizePx, response.body, UsgsImagery.CREDIT, clock())
    }
}
