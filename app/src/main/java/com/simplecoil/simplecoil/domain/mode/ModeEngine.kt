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

/**
 * Domination and Hardpoint on the dedicated server (map design: Domination, Hardpoint): who is
 * on which point, capture progress, scoring and the winner. Fed once a second with who stands
 * where; plain logic with no clock of its own, so a test can drive it.
 *
 * Domination: a team alone on a point takes it over [ObjectiveRules.captureSeconds] (faster
 * with more players, if set); two teams on it and nothing moves; nobody on it and part-taken
 * progress drains away. Every tick each team scores for each point it holds.
 *
 * Hardpoint: one point counts at a time, moving to the next every [ObjectiveRules.moveSeconds];
 * a team alone on it scores every tick.
 */
class ModeEngine(
    private val mode: GameMode,
    private val objectives: List<Objective>,
    private val rules: ObjectiveRules,
    startMillis: Long,
) {
    private val owner = HashMap<String, Int>()
    private val capturing = HashMap<String, Int>()
    private val progress = HashMap<String, Double>()
    private val contested = HashSet<String>()
    private val scores = HashMap<Int, Int>()
    private var lastMillis = startMillis
    private var nextTickAt = startMillis + rules.tickSeconds * 1000L
    private var active = 0
    private var movesAt = if (mode == GameMode.HARDPOINT && objectives.size > 1) startMillis + rules.moveSeconds * 1000L else 0L
    private var winner = 0

    /**
     * Moves the game on to [nowMillis]. [presence] is how many players of each team stand on
     * each point (by objective id, then team), counting only players who are in the game.
     */
    fun tick(nowMillis: Long, presence: Map<String, Map<Int, Int>>): ModeState {
        val dt = (nowMillis - lastMillis).coerceAtLeast(0)
        lastMillis = nowMillis
        if (winner != 0 || objectives.isEmpty()) return state(nowMillis)

        if (movesAt != 0L && nowMillis >= movesAt) {
            // Hardpoint: on to the next point, nobody holding it
            active = (active + 1) % objectives.size
            owner.clear()
            movesAt += rules.moveSeconds * 1000L
        }

        for ((index, objective) in objectives.withIndex()) {
            val id = objective.id
            val teams = presence[id].orEmpty().filterValues { it > 0 }
            contested.remove(id)
            if (mode == GameMode.HARDPOINT) {
                // Only the active point counts; it is held by whoever is alone on it
                capturing.remove(id)
                progress.remove(id)
                if (index != active) continue
                when (teams.size) {
                    0 -> owner.remove(id)
                    1 -> owner[id] = teams.keys.single()
                    else -> {
                        owner.remove(id)
                        contested += id
                    }
                }
                continue
            }
            when (teams.size) {
                0 -> drain(id, dt)
                1 -> {
                    val (team, count) = teams.entries.single()
                    if (owner[id] == team) {
                        drain(id, dt)
                    } else {
                        if (capturing[id] != team) {
                            capturing[id] = team
                            progress[id] = 0.0
                        }
                        val speed = if (rules.moreCaptureFaster) (1.0 + (count - 1) * 0.5).coerceAtMost(MAX_SPEED) else 1.0
                        val now = (progress[id] ?: 0.0) + dt * speed / (rules.captureSeconds * 1000.0)
                        if (now >= 1.0) {
                            owner[id] = team
                            capturing.remove(id)
                            progress.remove(id)
                        } else {
                            progress[id] = now
                        }
                    }
                }
                else -> contested += id
            }
        }

        while (nowMillis >= nextTickAt) {
            nextTickAt += rules.tickSeconds * 1000L
            owner.values.forEach { team -> scores[team] = (scores[team] ?: 0) + rules.pointsPerTick }
        }
        val best = scores.filterValues { it >= rules.scoreToWin }.maxByOrNull { it.value }
        if (best != null) winner = best.key
        return state(nowMillis)
    }

    /** Part-taken progress drains at the rate it was gained. */
    private fun drain(id: String, dt: Long) {
        val left = (progress[id] ?: return) - dt / (rules.captureSeconds * 1000.0)
        if (left <= 0) {
            progress.remove(id)
            capturing.remove(id)
        } else {
            progress[id] = left
        }
    }

    private fun state(nowMillis: Long): ModeState {
        val hardpoint = mode == GameMode.HARDPOINT
        val showNext = hardpoint && movesAt != 0L && rules.showNextSeconds > 0 && nowMillis >= movesAt - rules.showNextSeconds * 1000L
        val next = (active + 1) % objectives.size.coerceAtLeast(1)
        return ModeState(
            mode = mode,
            objectives = objectives.mapIndexed { index, o ->
                ObjectiveState(o.id, o.point, owner[o.id] ?: 0, capturing[o.id] ?: 0, progress[o.id] ?: 0.0, o.id in contested,
                    active = !hardpoint || index == active, next = showNext && index == next && next != active)
            },
            scores = scores.toMap(),
            scoreToWin = rules.scoreToWin,
            radiusMeters = rules.radiusMeters,
            winner = winner,
            movesAt = movesAt,
        )
    }

    private companion object {
        /** Up to twice as fast with more players. */
        const val MAX_SPEED = 2.0
    }
}
