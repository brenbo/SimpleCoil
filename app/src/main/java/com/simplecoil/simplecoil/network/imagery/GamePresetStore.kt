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
import com.simplecoil.simplecoil.domain.mode.GamePreset
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * The dedicated server's saved game setups (map design: Presets), one JSON file each in [dir].
 * Like [FieldPresetStore], which keeps the fields. File work blocks: call it off the main thread.
 */
class GamePresetStore(private val dir: File) {

    /** The saved setups' names, A to Z. */
    fun names(): List<String> = synchronized(this) {
        dir.listFiles()?.mapNotNull { read(it)?.name?.takeIf { name -> name.isNotBlank() } }?.sortedBy { it.lowercase() } ?: emptyList()
    }

    /** Saves [preset] under its name, replacing one saved under that name. */
    @Throws(IOException::class)
    fun save(preset: GamePreset): Unit = synchronized(this) {
        require(preset.name.isNotBlank()) { "a preset needs a name" }
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("can't create $dir")
        fileFor(preset.name).writeText(preset.toJson().toString(), Charsets.UTF_8)
    }

    fun load(name: String): GamePreset? = synchronized(this) { read(fileFor(name)) }

    fun delete(name: String): Unit = synchronized(this) {
        fileFor(name).delete()
    }

    private fun read(file: File): GamePreset? {
        if (!file.isFile) return null
        return try {
            GamePreset.fromJson(JSONObject(file.readText(Charsets.UTF_8)))
        } catch (e: JSONException) {
            Log.w(TAG, "Skipping a damaged game preset: " + e.message)
            null
        } catch (e: IOException) {
            null
        }
    }

    private fun fileFor(name: String): File =
        File(dir, name.trim().toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it) } + ".json")

    private companion object {
        const val TAG = "GamePresetStore"
    }
}
