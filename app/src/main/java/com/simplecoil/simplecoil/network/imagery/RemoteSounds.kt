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

import com.simplecoil.simplecoil.hardware.audio.AudioSettings
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * The sound settings the server operator set for players, served by the field service
 * (`SCFIELD1 SOUND <player id>`), since the game protocol is frozen. Each setting has a
 * [Assigned.revision], so a phone applies it once and keeps its own changes after that.
 */
class RemoteSounds(private val clock: () -> Long = System::currentTimeMillis) {

    data class Assigned(val revision: Long, val settings: AudioSettings) {
        fun toJson(): JSONObject = JSONObject()
            .put("rev", revision)
            .put("fx", settings.effectsVolume.toDouble())
            .put("voice", settings.voiceVolume.toDouble())
            .put("voiceOn", settings.voiceOn)
            .put("essential", settings.essentialOnly)
            .put("vibrate", settings.vibration)

        companion object {
            fun fromJson(json: JSONObject) = Assigned(
                json.getLong("rev"),
                AudioSettings(
                    effectsVolume = json.getDouble("fx").toFloat().coerceIn(0f, 1f),
                    voiceVolume = json.getDouble("voice").toFloat().coerceIn(0f, 1f),
                    voiceOn = json.getBoolean("voiceOn"),
                    essentialOnly = json.getBoolean("essential"),
                    vibration = json.getBoolean("vibrate"),
                ),
            )
        }
    }

    private val byPlayer = ConcurrentHashMap<Int, Assigned>()
    private var lastRevision = 0L

    /** What the server last set for [playerId], or null if it never did. */
    operator fun get(playerId: Int): AudioSettings? = byPlayer[playerId]?.settings

    /** Sets [settings] for each of [playerIds]; they get them the next time they ask. */
    @Synchronized
    fun set(playerIds: Collection<Int>, settings: AudioSettings) {
        // Always later than the last, even if the clock steps back
        lastRevision = maxOf(clock(), lastRevision + 1)
        for (id in playerIds) byPlayer[id] = Assigned(lastRevision, settings)
    }

    fun clear() = byPlayer.clear()

    /** The SOUND reply's body for [playerId], or null for NONE. */
    fun body(playerId: Int): ByteArray? = byPlayer[playerId]?.toJson()?.toString()?.toByteArray(Charsets.UTF_8)
}
