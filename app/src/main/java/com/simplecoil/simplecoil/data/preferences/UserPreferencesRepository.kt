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
import android.content.SharedPreferences
import androidx.core.content.edit
import com.simplecoil.simplecoil.domain.session.SessionPreferences
import com.simplecoil.simplecoil.domain.team.TeamMode
import com.simplecoil.simplecoil.hardware.audio.AudioSettings

/**
 * The player's saved settings: SharedPreferences "SimpleCoil" with exactly the keys and types the
 * app has always used (persisted user data is a compatibility surface, §2.1.4). Each save is one
 * apply(), grouped as the activity grouped them.
 */
class UserPreferencesRepository(private val prefs: SharedPreferences) : SessionPreferences {

    constructor(context: Context) : this(context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE))

    fun playerName(default: String): String = prefs.getString(PLAYER_NAME, default) ?: default
    fun savePlayerName(name: String) = prefs.edit { putString(PLAYER_NAME, name) }

    fun playerId(): Int = prefs.getInt(PLAYER_ID, 0)
    fun selectedPlayerId(): Int = prefs.getInt(SELECTED_PLAYER_ID, 0)

    /** The team buttons / server-assigned id: player id, selected id and team mode together. */
    fun savePlayerIdAndTeamMode(playerId: Int, teamMode: TeamMode) =
        prefs.edit {
            putInt(PLAYER_ID, playerId)
            putInt(SELECTED_PLAYER_ID, playerId)
            putInt(TEAM_MODE, teamMode.wireValue)
        }

    fun firingMode(default: Int): Int = prefs.getInt(FIRING_MODE, default)
    fun saveFiringMode(firingMode: Int) = prefs.edit { putInt(FIRING_MODE, firingMode) }

    fun shotMode(default: Int): Int = prefs.getInt(SHOT_MODE, default)
    override fun saveShotMode(shotMode: Int) = prefs.edit { putInt(SHOT_MODE, shotMode) }

    fun recoilEnabled(): Boolean = prefs.getBoolean(RECOIL_ENABLED, true)
    override fun saveRecoilEnabled(enabled: Boolean) = prefs.edit { putBoolean(RECOIL_ENABLED, enabled) }

    /** Shared by the player screen and the dedicated server. */
    fun teamMode(): TeamMode = TeamMode.fromWireOrDefault(prefs.getInt(TEAM_MODE, TeamMode.DEFAULT.wireValue))
    fun saveTeamMode(teamMode: TeamMode) = prefs.edit { putInt(TEAM_MODE, teamMode.wireValue) }

    /** The team mode a game mode (by its wire name) was last played with on this server; null if never. */
    fun teamModeFor(gameMode: String): TeamMode? =
        prefs.getInt(TEAM_MODE_FOR + gameMode, 0).takeIf { it != 0 }?.let { TeamMode.fromWire(it) }

    fun saveTeamModeFor(gameMode: String, teamMode: TeamMode) = prefs.edit { putInt(TEAM_MODE_FOR + gameMode, teamMode.wireValue) }

    fun timeLimit(): Int = prefs.getInt(LIMIT_TIME, 0)
    fun livesLimit(): Int = prefs.getInt(LIMIT_LIVES, 0)
    fun scoreLimit(): Int = prefs.getInt(LIMIT_SCORE, 0)
    fun saveLimits(timeLimit: Int, scoreLimit: Int, livesLimit: Int) =
        prefs.edit {
            putInt(LIMIT_TIME, timeLimit)
            putInt(LIMIT_SCORE, scoreLimit)
            putInt(LIMIT_LIVES, livesLimit)
        }

    /** The starting timer in seconds; the dedicated server's. */
    fun startTime(default: Int): Int = prefs.getInt(START_TIME, default)
    fun saveStartTime(seconds: Int) = prefs.edit { putInt(START_TIME, seconds) }

    /** The dedicated server's GPS relay mode (GameSettingsRepository.GPS_*). */
    fun gpsMode(default: Int): Int = prefs.getInt(GPS_MODE, default)
    fun saveGpsMode(mode: Int) = prefs.edit { putInt(GPS_MODE, mode) }

    /** The dedicated server lets players pick their own team. */
    fun allowChooseTeams(): Boolean = prefs.getBoolean(ALLOW_CHOOSE_TEAMS, true)
    fun saveAllowChooseTeams(allow: Boolean) = prefs.edit { putBoolean(ALLOW_CHOOSE_TEAMS, allow) }

    fun deviceAddress(): String = prefs.getString(DEVICE_ADDRESS, "") ?: ""
    fun saveDeviceAddress(address: String) = prefs.edit { putString(DEVICE_ADDRESS, address) }

    /** The map turns with the phone (heading-up) rather than keeping north up. */
    fun mapHeadingUp(): Boolean = prefs.getBoolean(MAP_HEADING_UP, true)
    fun saveMapHeadingUp(headingUp: Boolean) = prefs.edit { putBoolean(MAP_HEADING_UP, headingUp) }

    /** The map's auto zoom button (AutoZoom's name); null until the player picks one. */
    /** The player's sound choices (map design: Audio settings). */
    fun audioSettings(): AudioSettings = AudioSettings(
        effectsVolume = prefs.getInt(EFFECTS_VOLUME, 100) / 100f,
        voiceVolume = prefs.getInt(VOICE_VOLUME, 100) / 100f,
        voiceOn = prefs.getBoolean(VOICE_ON, true),
        essentialOnly = prefs.getBoolean(VOICE_ESSENTIAL_ONLY, false),
        vibration = prefs.getBoolean(VIBRATION_ON, true),
    )

    fun saveAudioSettings(settings: AudioSettings) = prefs.edit {
        putInt(EFFECTS_VOLUME, (settings.effectsVolume * 100).toInt())
        putInt(VOICE_VOLUME, (settings.voiceVolume * 100).toInt())
        putBoolean(VOICE_ON, settings.voiceOn)
        putBoolean(VOICE_ESSENTIAL_ONLY, settings.essentialOnly)
        putBoolean(VIBRATION_ON, settings.vibration)
    }

    fun mapAutoZoom(): String? = prefs.getString(MAP_AUTO_ZOOM, null)
    fun saveMapAutoZoom(mode: String) = prefs.edit { putString(MAP_AUTO_ZOOM, mode) }

    /** Where the dedicated server's field map was left: latitude, longitude and zoom; null if never. */
    fun fieldMapCamera(): DoubleArray? {
        val parts = prefs.getString(FIELD_MAP_CAMERA, null)?.split(',')?.mapNotNull { it.toDoubleOrNull() } ?: return null
        return if (parts.size == 3) parts.toDoubleArray() else null
    }
    fun saveFieldMapCamera(latitude: Double, longitude: Double, zoom: Double) =
        prefs.edit { putString(FIELD_MAP_CAMERA, "$latitude,$longitude,$zoom") }

    companion object {
        const val PREF_NAME = "SimpleCoil"
        const val PLAYER_NAME = "PlayerName"
        const val PLAYER_ID = "PlayerID"
        const val SELECTED_PLAYER_ID = "SelectedPlayerID"
        const val FIRING_MODE = "FiringMode"
        const val SHOT_MODE = "ShotMode"
        const val RECOIL_ENABLED = "RecoilEnabled"
        /** Stored under its old name so existing installs keep their setting. */
        const val TEAM_MODE = "GameMode"
        const val START_TIME = "StartTime"
        const val LIMIT_TIME = "TimeLimit"
        const val LIMIT_LIVES = "LivesLimit"
        const val LIMIT_SCORE = "ScoreLimit"
        const val DEVICE_ADDRESS = "DeviceAddress"
        const val GPS_MODE = "GPSMode"
        const val ALLOW_CHOOSE_TEAMS = "AllowChooseTeams"
        const val MAP_HEADING_UP = "MapHeadingUp"
        const val MAP_AUTO_ZOOM = "MapAutoZoom"
        const val FIELD_MAP_CAMERA = "FieldMapCamera"
        const val TEAM_MODE_FOR = "TeamModeFor_"
        const val EFFECTS_VOLUME = "EffectsVolume"
        const val VOICE_VOLUME = "VoiceVolume"
        const val VOICE_ON = "VoiceCallouts"
        const val VOICE_ESSENTIAL_ONLY = "VoiceEssentialOnly"
        const val VIBRATION_ON = "VibrationCues"
    }
}
