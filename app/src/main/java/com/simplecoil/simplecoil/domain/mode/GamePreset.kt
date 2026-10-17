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

package com.simplecoil.simplecoil.domain.mode

import com.simplecoil.simplecoil.domain.loadout.LoadoutRules
import com.simplecoil.simplecoil.domain.map.BorderRules
import com.simplecoil.simplecoil.domain.map.FieldConfig
import com.simplecoil.simplecoil.domain.map.SpawnRules
import com.simplecoil.simplecoil.domain.team.TeamMode
import org.json.JSONObject

/**
 * A saved game setup (map design: Presets, "Saturday BR"): the mode and every rule, plus the
 * server's team mode, GPS sharing, limits and starting timer. Not the field itself: border,
 * spawns, capture points and photo are a field preset, and the two combine freely.
 */
data class GamePreset(
    val name: String,
    val mode: GameMode,
    val objectiveRules: ObjectiveRules,
    val borderRules: BorderRules,
    val spawnRules: SpawnRules,
    val loadout: LoadoutRules,
    val teamMode: TeamMode,
    /** GameSettingsRepository.GPS_*: off, teammates or all. */
    val gpsMode: Int,
    /** Minutes, lives and score, as the game limit dialog shows them: 0 = off. */
    val timeLimit: Int,
    val livesLimit: Int,
    val scoreLimit: Int,
    val startTimeSeconds: Long,
    val allowChooseTeams: Boolean,
) {
    /** [field] with this preset's mode and rules; its border, spawns and points stay. */
    fun applyTo(field: FieldConfig): FieldConfig = field.copy(
        mode = mode, objectiveRules = objectiveRules, rules = borderRules, spawnRules = spawnRules, loadout = loadout,
    )

    fun toJson(): JSONObject = JSONObject()
        .put("version", VERSION)
        .put("name", name)
        .put("mode", mode.wireValue)
        .put("objectiveRules", objectiveRules.toJson())
        .put("rules", borderRules.toJson())
        .put("spawnRules", spawnRules.toJson())
        .put("loadout", loadout.toJson())
        .put("teamMode", teamMode.name)
        .put("gpsMode", gpsMode)
        .put("timeLimit", timeLimit)
        .put("livesLimit", livesLimit)
        .put("scoreLimit", scoreLimit)
        .put("startTime", startTimeSeconds)
        .put("allowChooseTeams", allowChooseTeams)

    companion object {
        const val VERSION = 1

        fun fromJson(json: JSONObject): GamePreset = GamePreset(
            name = json.optString("name", ""),
            mode = GameMode.fromWire(json.optString("mode")),
            objectiveRules = ObjectiveRules.fromJson(json.optJSONObject("objectiveRules")),
            borderRules = BorderRules.fromJson(json.optJSONObject("rules")),
            spawnRules = SpawnRules.fromJson(json.optJSONObject("spawnRules")),
            loadout = LoadoutRules.fromJson(json.optJSONObject("loadout")),
            teamMode = TeamMode.entries.firstOrNull { it.name == json.optString("teamMode") } ?: TeamMode.TWO_TEAMS,
            gpsMode = json.optInt("gpsMode", 0),
            timeLimit = json.optInt("timeLimit", 0),
            livesLimit = json.optInt("livesLimit", 0),
            scoreLimit = json.optInt("scoreLimit", 0),
            startTimeSeconds = json.optLong("startTime", 0L),
            allowChooseTeams = json.optBoolean("allowChooseTeams", false),
        )
    }
}
