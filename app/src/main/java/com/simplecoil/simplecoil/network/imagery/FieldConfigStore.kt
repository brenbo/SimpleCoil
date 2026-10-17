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
import com.simplecoil.simplecoil.domain.map.FieldConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.IOException

/**
 * The field's border and rules this phone keeps, in [dir] (map design: Map objects). Like
 * [FieldPackStore]: the server keeps what the host set up, a player's phone what a server sent.
 * Thread-safe; file work blocks, so call it off the main thread (except [config]).
 */
class FieldConfigStore(private val dir: File) {

    private val file = File(dir, FILE_NAME)
    private val lock = Any()
    private var loaded = false
    private val _config = MutableStateFlow<FieldConfig?>(null)

    /** The kept config, or null if there is none. Call [refresh] once to read it from disk. */
    val config: StateFlow<FieldConfig?> = _config.asStateFlow()

    /** Reads the kept config from disk, if not done yet; returns it. */
    fun refresh(): FieldConfig? = synchronized(lock) {
        if (!loaded) {
            loaded = true
            _config.value = read()
        }
        _config.value
    }

    @Throws(IOException::class)
    fun save(config: FieldConfig): Unit = synchronized(lock) {
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("can't create $dir")
        val tmp = File(dir, "$FILE_NAME.tmp")
        tmp.writeBytes(config.toBytes())
        if (file.exists() && !file.delete()) throw IOException("can't replace $file")
        if (!tmp.renameTo(file)) throw IOException("can't move $tmp")
        loaded = true
        _config.value = config
    }

    /** The kept config as it goes over the network, or null if there is none. */
    fun bytes(): ByteArray? = synchronized(lock) { refresh()?.toBytes() }

    fun clear(): Unit = synchronized(lock) {
        file.delete()
        loaded = true
        _config.value = null
    }

    private fun read(): FieldConfig? {
        if (!file.isFile) return null
        return try {
            FieldConfig.fromBytes(file.readBytes())
        } catch (e: IOException) {
            Log.w(TAG, "Dropping a damaged field config: " + e.message)
            file.delete()
            null
        }
    }

    private companion object {
        const val TAG = "FieldConfigStore"
        const val FILE_NAME = "field.json"
    }
}
