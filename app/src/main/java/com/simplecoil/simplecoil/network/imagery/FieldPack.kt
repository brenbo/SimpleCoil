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

import com.simplecoil.simplecoil.domain.map.GeoBounds
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/** What a field imagery pack holds, besides the photo itself. */
data class FieldPackInfo(
    /** Changes whenever the photo or its placement does; phones compare it to skip a download. */
    val id: String,
    /** Where the photo's edges fall on the ground. */
    val bounds: GeoBounds,
    val widthPx: Int,
    val heightPx: Int,
    /** The imagery's required credit line, shown on the map. */
    val credit: String,
    /** When the server downloaded it (epoch milliseconds). */
    val createdAtMillis: Long,
) {
    fun toJson(): JSONObject = JSONObject()
        .put(KEY_ID, id)
        .put(KEY_NORTH, bounds.north)
        .put(KEY_SOUTH, bounds.south)
        .put(KEY_EAST, bounds.east)
        .put(KEY_WEST, bounds.west)
        .put(KEY_WIDTH, widthPx)
        .put(KEY_HEIGHT, heightPx)
        .put(KEY_CREDIT, credit)
        .put(KEY_CREATED, createdAtMillis)

    companion object {
        private const val KEY_ID = "id"
        private const val KEY_NORTH = "north"
        private const val KEY_SOUTH = "south"
        private const val KEY_EAST = "east"
        private const val KEY_WEST = "west"
        private const val KEY_WIDTH = "width"
        private const val KEY_HEIGHT = "height"
        private const val KEY_CREDIT = "credit"
        private const val KEY_CREATED = "created"

        /** @throws IOException if [json] is missing a field or holds nonsense */
        @Throws(IOException::class)
        fun fromJson(json: JSONObject): FieldPackInfo = try {
            FieldPackInfo(
                id = json.getString(KEY_ID),
                bounds = GeoBounds(json.getDouble(KEY_NORTH), json.getDouble(KEY_SOUTH), json.getDouble(KEY_EAST), json.getDouble(KEY_WEST)),
                widthPx = json.getInt(KEY_WIDTH),
                heightPx = json.getInt(KEY_HEIGHT),
                credit = json.optString(KEY_CREDIT, ""),
                createdAtMillis = json.optLong(KEY_CREATED, 0L),
            ).also {
                if (it.id.isEmpty() || it.widthPx <= 0 || it.heightPx <= 0) throw IOException("bad field pack info")
            }
        } catch (e: JSONException) {
            throw IOException("bad field pack info", e)
        } catch (e: IllegalArgumentException) {
            throw IOException("bad field pack bounds", e)
        }
    }
}

/**
 * One field's aerial photo and where it goes: what the dedicated server downloads and every
 * phone keeps (map design: Field imagery packs).
 */
class FieldPack(val info: FieldPackInfo, val image: ByteArray) {

    companion object {
        /** A new pack for [image] placed at [bounds]; its id is a hash of both. */
        fun create(bounds: GeoBounds, widthPx: Int, heightPx: Int, image: ByteArray, credit: String, createdAtMillis: Long): FieldPack {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(image)
            digest.update("${bounds.north},${bounds.south},${bounds.east},${bounds.west}".toByteArray(Charsets.UTF_8))
            val id = digest.digest().take(16).joinToString("") { "%02x".format(it) }
            return FieldPack(FieldPackInfo(id, bounds, widthPx, heightPx, credit, createdAtMillis), image)
        }
    }
}

/**
 * A pack as bytes: `SCFIELD1`, the info as JSON (length-prefixed), then the JPEG
 * (length-prefixed). The same bytes go on disk and over the network.
 */
object FieldPackCodec {
    const val MAGIC = "SCFIELD1"

    /** Packs bigger than this are refused: a 2048-pixel photo is a few MB. */
    const val MAX_PACK_BYTES = 16 * 1024 * 1024
    private const val MAX_INFO_BYTES = 64 * 1024

    fun encode(pack: FieldPack): ByteArray = ByteArrayOutputStream(pack.image.size + 512).also { write(it, pack) }.toByteArray()

    @Throws(IOException::class)
    fun write(output: OutputStream, pack: FieldPack) {
        val out = DataOutputStream(output)
        val info = pack.info.toJson().toString().toByteArray(Charsets.UTF_8)
        out.write(MAGIC.toByteArray(Charsets.US_ASCII))
        out.writeInt(info.size)
        out.write(info)
        out.writeInt(pack.image.size)
        out.write(pack.image)
        out.flush()
    }

    /** @throws IOException on anything but a whole, well-formed pack */
    @Throws(IOException::class)
    fun decode(bytes: ByteArray): FieldPack = read(bytes.inputStream())

    /** Reads one pack from [input]. @throws IOException on anything but a whole, well-formed pack */
    @Throws(IOException::class)
    fun read(input: InputStream): FieldPack {
        val inp = DataInputStream(input)
        val magic = ByteArray(MAGIC.length)
        inp.readFully(magic)
        if (String(magic, Charsets.US_ASCII) != MAGIC) throw IOException("not a field pack")
        val infoSize = inp.readInt()
        if (infoSize !in 1..MAX_INFO_BYTES) throw IOException("bad field pack info size $infoSize")
        val infoBytes = ByteArray(infoSize).also { inp.readFully(it) }
        val info = try {
            FieldPackInfo.fromJson(JSONObject(String(infoBytes, Charsets.UTF_8)))
        } catch (e: JSONException) {
            throw IOException("bad field pack info", e)
        }
        val imageSize = inp.readInt()
        if (imageSize !in 1..MAX_PACK_BYTES) throw IOException("bad field pack image size $imageSize")
        val image = ByteArray(imageSize).also { inp.readFully(it) }
        return FieldPack(info, image)
    }
}
