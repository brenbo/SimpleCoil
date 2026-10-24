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
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads a field's aerial photo on the dedicated server (map design: Field imagery packs):
 * from MapTiler when the build has its key, otherwise from USGS. Needs internet, at home or over
 * a hotspot at the field; blocks, so call it off the main thread.
 */
interface FieldDownloader {

    /** The photo of a square [sideMeters] across around [center], as a pack ready to keep. */
    @Throws(DownloadException::class)
    fun download(center: LatLon, sideMeters: Double): FieldPack

    /** What a request returned. */
    class Response(val code: Int, val contentType: String?, val body: ByteArray)

    /** Why a download gave no field. */
    class DownloadException(val reason: Reason, message: String, cause: Throwable? = null) : IOException(message, cause)

    enum class Reason {
        /** No connection, a timeout, or a server error. */
        NETWORK,

        /** There's no imagery there (outside the service's area, or open water). */
        NO_IMAGERY,

        /** The service turned us away: a wrong key, or the month's free requests used up. */
        REFUSED,
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 60_000

        fun isJpeg(bytes: ByteArray): Boolean =
            bytes.size > 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()

        @Throws(IOException::class)
        fun httpGet(url: String): Response {
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = CONNECT_TIMEOUT_MS
                conn.readTimeout = READ_TIMEOUT_MS
                conn.setRequestProperty("User-Agent", "SimpleCoil")
                val code = conn.responseCode
                val body = (if (code in 200..299) conn.inputStream else conn.errorStream)?.use { readAll(it) } ?: ByteArray(0)
                return Response(code, conn.contentType, body)
            } finally {
                conn.disconnect()
            }
        }

        private fun readAll(input: InputStream): ByteArray {
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
                if (out.size() > FieldPackCodec.MAX_PACK_BYTES) throw IOException("photo too big")
            }
            return out.toByteArray()
        }
    }
}
