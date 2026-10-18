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

import com.simplecoil.simplecoil.domain.map.GeoMath
import com.simplecoil.simplecoil.domain.map.LatLon
import com.simplecoil.simplecoil.domain.session.SessionSound
import com.simplecoil.simplecoil.domain.session.SessionState
import com.simplecoil.simplecoil.domain.session.SpawnPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The Battle Royale zone on a player's phone (map design: Battle Royale): outside it, the player
 * loses health every few seconds, more each phase; once respawns are over, an elimination is for
 * good. Checked on the phone, like the border. Main thread.
 */
class ZoneController(
    private val state: StateFlow<ModeState?>,
    private val session: StateFlow<SessionState>,
    private val penalties: Penalties,
    /** True while a game is on. */
    private val inGame: () -> Boolean,
    private val feedback: Feedback,
    scope: CoroutineScope,
    private val clock: () -> Long,
) {
    interface Penalties {
        fun damage(amount: Int)
        fun setNoRespawn(on: Boolean)

        /** A new round: out players come back. */
        fun revive()
    }

    interface Feedback {
        fun play(sound: SessionSound)
        fun vibrate()
    }

    /** Outside the zone: how far in the safe edge is. */
    data class Outside(val metersToSafety: Double, val damage: Int)

    private val _outside = MutableStateFlow<Outside?>(null)

    /** Set while the player is outside the zone in a game; null otherwise. */
    val outside: StateFlow<Outside?> = _outside.asStateFlow()

    private var position: LatLon? = null
    /** When the player left the zone; null while inside. */
    private var outsideSince: Long? = null
    private var lastDamageAt = 0L

    init {
        scope.launch {
            while (true) {
                update()
                delay(TICK_MILLIS)
            }
        }
    }

    fun onFix(point: LatLon) {
        position = point
    }

    /** The round last seen, so a new one revives the players out of the last. */
    private var round = 0

    fun update() {
        val mode = state.value
        val zone = mode?.takeIf { it.mode == GameMode.BATTLE_ROYALE }?.zone
        val playing = inGame()
        // Battle Royale once respawns are over, or a round of Search & Destroy: eliminations are final
        penalties.setNoRespawn(playing && (zone?.noRespawn == true || mode?.noRespawn == true))
        val newRound = mode?.round ?: 0
        if (playing && newRound > round && round != 0) penalties.revive()
        round = if (playing) newRound else 0
        val here = position
        val alive = session.value.spawn == SpawnPhase.None
        if (!playing || zone == null || here == null || !alive) {
            _outside.value = null
            outsideSince = null
            return
        }
        val toEdge = GeoMath.distanceMeters(here, zone.current.center) - zone.current.radiusMeters
        if (toEdge <= 0) {
            _outside.value = null
            outsideSince = null
            return
        }
        val now = clock()
        if (outsideSince == null) {
            // Just left: a warning first, the damage after the first tick
            outsideSince = now
            lastDamageAt = now
            feedback.vibrate()
            feedback.play(SessionSound.BEEP)
        } else if (now - lastDamageAt >= zone.tickSeconds * 1000L) {
            lastDamageAt = now
            penalties.damage(zone.damage)
        }
        _outside.value = Outside(toEdge, zone.damage)
    }

    private companion object {
        const val TICK_MILLIS = 500L
    }
}
