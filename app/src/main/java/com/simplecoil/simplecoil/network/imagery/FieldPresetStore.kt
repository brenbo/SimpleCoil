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

import com.simplecoil.simplecoil.domain.map.FieldConfig
import java.io.File
import java.io.IOException

/**
 * The dedicated server's saved fields (map design: Field presets): each a name, a photo and a
 * config, kept in [dir] so the host sets up a park once and reuses it. Loading one copies it
 * into the server's current field. Blocks; call it off the main thread.
 */
class FieldPresetStore(private val dir: File) {

    /** The saved fields' names, A to Z. */
    fun names(): List<String> = synchronized(this) {
        dir.listFiles()?.filter { it.isDirectory }?.mapNotNull { nameOf(it) }?.sortedBy { it.lowercase() } ?: emptyList()
    }

    /** Saves [pack] and [config] as [name], replacing a field saved under that name. */
    @Throws(IOException::class)
    fun save(name: String, pack: FieldPack?, config: FieldConfig): Unit = synchronized(this) {
        require(name.isNotBlank()) { "a preset needs a name" }
        val folder = folderFor(name)
        folder.deleteRecursively()
        FieldConfigStore(folder).save(config.copy(name = name))
        if (pack != null) FieldPackStore(folder).save(pack)
    }

    /** The field saved as [name]: its photo (if it has one) and config; null if there's no such field. */
    fun load(name: String): Pair<FieldPack?, FieldConfig>? = synchronized(this) {
        val folder = folderFor(name)
        val config = FieldConfigStore(folder).refresh() ?: return null
        FieldPackStore(folder).load() to config
    }

    fun delete(name: String): Unit = synchronized(this) {
        folderFor(name).deleteRecursively()
    }

    private fun folderFor(name: String): File =
        File(dir, name.trim().toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it) })

    private fun nameOf(folder: File): String? = FieldConfigStore(folder).refresh()?.name?.takeIf { it.isNotBlank() }
}
