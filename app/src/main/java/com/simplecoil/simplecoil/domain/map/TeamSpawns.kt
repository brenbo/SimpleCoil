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

package com.simplecoil.simplecoil.domain.map

import com.simplecoil.simplecoil.domain.team.TeamMode
import kotlin.random.Random

/**
 * A field's spawns for the team mode being played (the server applies this before a field goes
 * to the game and the phones; the field map keeps every spawn the host put down):
 * - Teams: only the spawns of the teams in play (and anyone's). A team in play with none of
 *   its own, and no spawn for anyone, gets them auto-placed, clear of the others.
 * - Free for all and squads: everyone's spawns; spawns made for teams count for everyone.
 */
object TeamSpawns {

    fun forMode(config: FieldConfig, mode: TeamMode, random: Random = Random.Default): FieldConfig {
        val spawns = config.spawns
        if (spawns.isEmpty()) return config
        val result = if (mode.isTeamless) {
            spawns.filter { it.team == Spawn.ANYONE }.ifEmpty { spawns.map { it.copy(team = Spawn.ANYONE) } }
        } else {
            val teams = 1..mode.teamCount
            val kept = spawns.filter { it.team == Spawn.ANYONE || it.team in teams }
            val missing = if (kept.any { it.team == Spawn.ANYONE }) emptyList() else teams.filter { t -> kept.none { it.team == t } }
            kept + placed(config, kept, missing, random)
        }
        return if (result == spawns) config else config.copy(spawns = result)
    }

    /** Spawns for the [missing] teams, as many each as the most any team has, clear of [kept]. */
    private fun placed(config: FieldConfig, kept: List<Spawn>, missing: List<Int>, random: Random): List<Spawn> {
        val border = config.border ?: return emptyList()
        if (missing.isEmpty()) return emptyList()
        val perTeam = kept.groupingBy { it.team }.eachCount().values.maxOrNull() ?: config.spawnRules.perTeam
        // Away from the other teams' spawns (a rule Auto-place loosens on a small field)
        return SpawnPlanner.place(border, missing, config.spawnRules.copy(perTeam = perTeam.coerceIn(1, 4)), config.rules.edgeBufferMeters,
            enemies = kept.groupBy({ it.team }, { it.point }), random = random, keepClear = SpawnPlanner.objectiveClearance(config)).spawns
    }
}

/**
 * [TeamSpawns.forMode], worked out once per field and team mode, with the capture points the
 * host left to the server placed too ([withPlacedObjectives]): auto-placed spawns and points
 * stay put (the phones see them before the game, and the game uses the same ones) until the
 * field or the team mode changes.
 */
class TeamSpawnsCache(private val random: Random = Random.Default) {
    private var key: Pair<String, TeamMode>? = null
    private var prepared: FieldConfig? = null

    @Synchronized
    fun forMode(config: FieldConfig, mode: TeamMode): FieldConfig {
        val k = config.id to mode
        if (k != key) {
            key = k
            prepared = withPlacedObjectives(TeamSpawns.forMode(config, mode, random), random)
        }
        return prepared!!
    }
}

/**
 * [config] with its capture points: the host's, unless there are none (or Hardpoint has too few
 * to move between), then ones placed by the rules, clear of the spawns.
 */
fun withPlacedObjectives(config: FieldConfig, random: Random = Random.Default): FieldConfig {
    val border = config.border
    if (!config.mode.hasObjectives || border == null) return config
    val enough = if (config.mode == com.simplecoil.simplecoil.domain.mode.GameMode.HARDPOINT) config.objectives.size >= 2 else config.objectives.isNotEmpty()
    if (enough) return config
    val count = com.simplecoil.simplecoil.domain.mode.ObjectivePlanner.count(config.mode, border, config.objectiveRules)
    val placed = com.simplecoil.simplecoil.domain.mode.ObjectivePlanner.place(border, count, config.objectiveRules, config.spawns,
        config.spawnRules.radiusMeters, config.rules.edgeBufferMeters, random)
    return config.copy(objectives = placed)
}
