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

package com.simplecoil.simplecoil.data.preferences

import android.content.Context
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * This install's own id: a random UUID made the first time the app needs it and kept until the
 * app is uninstalled (or its data cleared). The hello sends it so the server knows a phone that
 * drops off the Wi-Fi and comes back, whichever blaster it has, or none.
 *
 * It lives in the no-backup folder, so a backup restored onto another phone doesn't bring it along
 * and make two phones one player.
 */
object InstallId {
    private const val FILE = "install_id"

    @Volatile private var cached: String? = null

    fun of(context: Context): String = cached ?: synchronized(this) {
        cached ?: read(File(context.noBackupFilesDir, FILE)).also { cached = it }
    }

    /** The id kept in [file], or a new one written there. */
    internal fun read(file: File): String {
        try {
            val saved = file.takeIf { it.isFile }?.readText()?.trim()
            if (saved != null && isValid(saved)) return saved
        } catch (_: IOException) {
            // Unreadable: a new one below
        }
        val made = UUID.randomUUID().toString()
        try {
            file.writeText(made)
        } catch (_: IOException) {
            // Not kept: this run still has one, and the next makes another
        }
        return made
    }

    private fun isValid(id: String): Boolean = try {
        UUID.fromString(id).toString() == id
    } catch (_: IllegalArgumentException) {
        false
    }
}
