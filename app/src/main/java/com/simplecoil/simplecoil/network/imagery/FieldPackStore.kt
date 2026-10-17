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

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.IOException

/**
 * The one field pack this phone keeps, in [dir]. The dedicated server keeps the field it
 * downloaded; a player's phone keeps the last field a server sent it. Thread-safe; file work
 * blocks, so call it off the main thread (except [info], a cached value).
 */
class FieldPackStore(private val dir: File) {

    private val file = File(dir, FILE_NAME)
    private val lock = Any()

    private val _info = MutableStateFlow<FieldPackInfo?>(null)
    private var loaded = false

    /** The kept pack's info, or null if there is none. Call [refresh] once to read it from disk. */
    val info: StateFlow<FieldPackInfo?> = _info.asStateFlow()

    /** Reads the kept pack's info from disk, if not done yet; returns it. */
    fun refresh(): FieldPackInfo? = synchronized(lock) {
        if (!loaded) {
            loaded = true
            _info.value = readOrNull()?.info
        }
        _info.value
    }

    /** The kept pack, or null if there is none or it is damaged (a damaged one is deleted). */
    fun load(): FieldPack? = synchronized(lock) {
        val pack = readOrNull()
        loaded = true
        _info.value = pack?.info
        pack
    }

    /** Keeps [pack] in place of any other; written whole or not at all. */
    @Throws(IOException::class)
    fun save(pack: FieldPack): Unit = synchronized(lock) {
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("can't create $dir")
        val tmp = File(dir, "$FILE_NAME.tmp")
        tmp.outputStream().buffered().use { FieldPackCodec.write(it, pack) }
        if (file.exists() && !file.delete()) throw IOException("can't replace $file")
        if (!tmp.renameTo(file)) throw IOException("can't move $tmp")
        loaded = true
        _info.value = pack.info
    }

    /** The kept pack's bytes as they go over the network, or null if there is none. */
    fun bytes(): ByteArray? = synchronized(lock) {
        if (!file.isFile) null else try {
            file.readBytes()
        } catch (e: IOException) {
            null
        }
    }

    fun clear(): Unit = synchronized(lock) {
        file.delete()
        loaded = true
        _info.value = null
    }

    private fun readOrNull(): FieldPack? {
        if (!file.isFile) return null
        return try {
            file.inputStream().buffered().use { FieldPackCodec.read(it) }
        } catch (e: IOException) {
            Log.w(TAG, "Dropping a damaged field pack: " + e.message)
            file.delete()
            null
        }
    }

    private companion object {
        const val TAG = "FieldPackStore"
        const val FILE_NAME = "field.scfield"
    }
}
