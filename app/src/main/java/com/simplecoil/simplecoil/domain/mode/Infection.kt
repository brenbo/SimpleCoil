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

import org.json.JSONObject
import kotlin.random.Random

/** Infection settings (map design: Infection). */
data class InfectionRules(
    val startingInfected: Int = 1,
    /** How often survivors show on the infected's maps; 0 = never. */
    val revealEverySeconds: Int = 60,
    val roundMinutes: Int = 10,
) {
    fun toJson(): JSONObject = JSONObject().put("starting", startingInfected).put("reveal", revealEverySeconds).put("round", roundMinutes)

    fun clamped(): InfectionRules = copy(
        startingInfected = startingInfected.coerceIn(1, 3),
        revealEverySeconds = revealEverySeconds.coerceIn(0, 180),
        roundMinutes = roundMinutes.coerceIn(3, 30),
    )

    companion object {
        fun fromJson(json: JSONObject?): InfectionRules {
            val d = InfectionRules()
            if (json == null) return d
            return InfectionRules(json.optInt("starting", d.startingInfected), json.optInt("reveal", d.revealEverySeconds),
                json.optInt("round", d.roundMinutes)).clamped()
        }
    }
}

/**
 * Infection on the dedicated server (map design: Infection), best played as free for all: a
 * few players start infected, and anyone the infected eliminate joins them. Survivors who last
 * the round win; if everyone is infected, the infected do. The two sides are [SURVIVORS] and
 * [INFECTED], not the server's teams.
 */
class InfectionEngine(private val rules: InfectionRules, private val startMillis: Long, private val random: Random = Random.Default) {
    private val infected = HashSet<Int>()
    private var started = false
    private var winner = 0
    private val endsAt = startMillis + rules.roundMinutes * 60_000L

    /** [victim] eliminated by [shooter]: infected if the shooter is. */
    fun onEliminated(victim: Int, shooter: Int) {
        if (shooter in infected) infected += victim
    }

    fun tick(nowMillis: Long, players: Map<Int, PlayerInfo>): ModeState {
        if (!started && players.size >= 2) {
            // The first infected, picked at random once there are players
            started = true
            players.keys.shuffled(random).take(rules.startingInfected.coerceAtMost(players.size - 1)).forEach { infected += it }
        }
        val survivors = players.keys.filter { it !in infected }
        if (started && winner == 0) {
            when {
                survivors.isEmpty() -> winner = INFECTED
                nowMillis >= endsAt -> winner = SURVIVORS
            }
        }
        // Survivors show on the infected's maps for a few seconds in every interval
        val reveal = rules.revealEverySeconds > 0 && ((nowMillis - startMillis) % (rules.revealEverySeconds * 1000L)) < REVEAL_MILLIS
        return ModeState(
            mode = GameMode.INFECTION, objectives = emptyList(),
            scores = mapOf(SURVIVORS to survivors.size, INFECTED to players.keys.count { it in infected }),
            scoreToWin = 0, radiusMeters = 0.0, winner = winner, movesAt = endsAt,
            infected = infected.toSet(),
            revealed = if (reveal) survivors.mapNotNull { id -> players[id]?.point?.let { id to it } }.toMap() else emptyMap(),
        )
    }

    companion object {
        /** The two sides, apart from the server's teams (and player ids). */
        const val SURVIVORS = -5
        const val INFECTED = -4
        private const val REVEAL_MILLIS = 5_000L
    }
}

/** The side [playerId] is on in [this] game: Infection's survivors or infected, else [team]. */
fun ModeState.sideOf(playerId: Int, team: Int): Int = when (mode) {
    GameMode.INFECTION -> if (playerId in infected) InfectionEngine.INFECTED else InfectionEngine.SURVIVORS
    else -> team
}
