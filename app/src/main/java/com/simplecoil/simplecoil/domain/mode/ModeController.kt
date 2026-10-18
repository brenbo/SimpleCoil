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

import com.simplecoil.simplecoil.domain.map.FieldConfig
import com.simplecoil.simplecoil.domain.session.SessionSound
import com.simplecoil.simplecoil.domain.session.SessionState
import com.simplecoil.simplecoil.domain.session.SpawnPhase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * An objective game on a player's phone (map design: Domination, Hardpoint): fetches the
 * server's state every second while one runs, and tells the player when a point changes hands.
 * Main thread.
 */
class ModeController(
    private val field: StateFlow<FieldConfig?>,
    private val session: StateFlow<SessionState>,
    private val server: Server,
    /** This player's id and team (in free for all, their own id). */
    private val me: () -> Pair<Int, Int>,
    /** True while a game is on (running, or eliminated and waiting to spawn). */
    private val inGame: () -> Boolean,
    private val feedback: Feedback,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
) {
    interface Server {
        /** Blocks; called off the main thread. */
        fun state(playerId: Int, alive: Boolean): ModeState?
    }

    interface Feedback {
        fun play(sound: SessionSound)
        fun vibrate()
    }

    /** Something about the points to tell the player. */
    sealed interface Notice {
        val id: String

        /** Your team took a point. */
        data class Secured(override val id: String) : Notice

        /** Your team lost one. */
        data class Lost(override val id: String) : Notice

        /** Another team is taking one your team holds. */
        data class UnderAttack(override val id: String) : Notice

        /** Hardpoint: the point moved here. */
        data class Moved(override val id: String) : Notice
    }

    private val _state = MutableStateFlow<ModeState?>(null)

    /** The game as the server last told it; null outside an objective game, kept after one ends. */
    val state: StateFlow<ModeState?> = _state.asStateFlow()

    private val _notices = MutableSharedFlow<Notice>(extraBufferCapacity = 8)
    val notices: SharedFlow<Notice> = _notices.asSharedFlow()

    private var wasInGame = false

    init {
        scope.launch {
            while (true) {
                poll()
                delay(POLL_MILLIS)
            }
        }
    }

    /** The host pressed Reset: the last game's result and scores go. */
    fun clear() {
        _state.value = null
    }

    private suspend fun poll() {
        val playing = inGame()
        val objective = field.value?.mode?.onServer == true
        if (playing && !wasInGame) _state.value = null // a new game
        val ended = !playing && wasInGame
        wasInGame = playing
        if (!objective) return
        if (ended) {
            // One last look, for the winner: the server ended the game on reaching the score
            fetch(alive = false)
            return
        }
        if (playing) fetch(alive = session.value.spawn == SpawnPhase.None)
    }

    private suspend fun fetch(alive: Boolean) {
        val (id, _) = me()
        val next = withContext(io) { server.state(id, alive) } ?: return
        val previous = _state.value
        _state.value = next
        if (previous != null) announce(previous, next)
    }

    /** What changed for this player's team between [before] and [after]. */
    fun announce(before: ModeState, after: ModeState) {
        val (id, layoutTeam) = me()
        val team = after.sideOf(id, layoutTeam)
        fun cue(sound: SessionSound, buzz: Boolean = true) {
            feedback.play(sound)
            if (buzz) feedback.vibrate()
        }
        // The game won or lost
        if (after.winner != 0 && before.winner == 0) {
            cue(if (after.winner == team) SessionSound.VICTORY else SessionSound.DEFEAT)
            return
        }
        when (after.mode) {
            GameMode.CAPTURE_THE_FLAG -> {
                if ((after.scores[team] ?: 0) > (before.scores[team] ?: 0)) cue(SessionSound.FLAG_CAPTURED)
                val mine = after.flags.firstOrNull { it.team == team }
                val was = before.flags.firstOrNull { it.team == team }
                if (mine != null && was != null && mine.carrier != 0 && was.carrier == 0) cue(SessionSound.FLAG_TAKEN)
                return
            }
            GameMode.EXTRACTION -> {
                if ((after.scores[team] ?: 0) > (before.scores[team] ?: 0)) cue(SessionSound.LOOT_BANKED)
                else if ((after.extraction?.carrying?.get(id) ?: 0) > (before.extraction?.carrying?.get(id) ?: 0)) cue(SessionSound.LOOT, buzz = false)
                return
            }
            GameMode.SEARCH_AND_DESTROY -> {
                val b = after.bomb ?: return
                val was = before.bomb ?: return
                when {
                    b.round > was.round -> cue(SessionSound.ROUND_START)
                    b.phase == RoundPhase.PLANTED && was.phase == RoundPhase.LIVE -> cue(SessionSound.BOMB_PLANTED)
                    // A round won while the bomb was down: defused
                    b.phase == RoundPhase.BETWEEN && was.phase == RoundPhase.PLANTED && b.lastRoundWinner != b.attackers -> cue(SessionSound.BOMB_DEFUSED)
                    // Won by the attackers while it was down: it went off
                    b.phase == RoundPhase.BETWEEN && was.phase == RoundPhase.PLANTED -> cue(SessionSound.BOMB_EXPLODED)
                    b.phase == RoundPhase.BETWEEN && was.phase != RoundPhase.BETWEEN ->
                        cue(if (b.lastRoundWinner == team) SessionSound.SECURED else SessionSound.LOST)
                }
                return
            }
            GameMode.INFECTION -> {
                if (id in after.infected && id !in before.infected) cue(SessionSound.INFECTED)
                return
            }
            GameMode.BATTLE_ROYALE -> {
                val z = after.zone ?: return
                if (z.shrinking && before.zone?.shrinking == false) cue(SessionSound.ZONE_CLOSING)
                return
            }
            GameMode.DOMINATION, GameMode.HARDPOINT -> {}
            GameMode.MARKSMAN, GameMode.CLASSIC -> return
        }
        for (now in after.objectives) {
            val was = before.objectives.firstOrNull { it.id == now.id } ?: continue
            when {
                now.owner == team && was.owner != team -> {
                    cue(SessionSound.SECURED)
                    _notices.tryEmit(Notice.Secured(now.id))
                }
                was.owner == team && now.owner != team && after.mode == GameMode.DOMINATION -> {
                    cue(SessionSound.LOST)
                    _notices.tryEmit(Notice.Lost(now.id))
                }
                now.owner == team && now.capturing != 0 && now.capturing != team && was.capturing != now.capturing -> {
                    cue(SessionSound.ENEMY_CAPTURING)
                    _notices.tryEmit(Notice.UnderAttack(now.id))
                }
                // Your team starting to take one
                now.capturing == team && was.capturing != team -> cue(SessionSound.CAPTURING, buzz = false)
                now.active && !was.active && after.mode == GameMode.HARDPOINT -> {
                    cue(SessionSound.POINT_MOVED)
                    _notices.tryEmit(Notice.Moved(now.id))
                }
            }
        }
    }

    companion object {
        const val POLL_MILLIS = 1_000L
    }
}
