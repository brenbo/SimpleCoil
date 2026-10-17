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

import com.simplecoil.simplecoil.domain.session.SessionSound
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Keeps players on the field during a game (map design: Border). Each phone checks its own
 * position against the border its server sent, warns its player, and applies the server's
 * penalty through the blaster session: the game protocol is frozen, so the server only hears the
 * result (an elimination). Main thread.
 */
class BorderController(
    private val field: StateFlow<FieldConfig?>,
    private val penalties: Penalties,
    /** True while a game is on (running, or eliminated and waiting to spawn). */
    private val inGame: () -> Boolean,
    private val feedback: Feedback,
    scope: CoroutineScope,
    private val clock: () -> Long,
) {
    /** What the blaster session does for a penalty. */
    interface Penalties {
        fun setOutOfBounds(disabled: Boolean)

        /** While held, a player whose respawn countdown ends stays out until back on the field. */
        fun holdRespawn(held: Boolean)
        fun damage(amount: Int)
        fun eliminate()
    }

    /** Sounds and vibration. */
    interface Feedback {
        fun play(sound: SessionSound)
        fun vibrate()
    }

    private val _status = MutableStateFlow<BorderTracker.Status?>(null)

    /** Where the player stands; null with no border or no game. */
    val status: StateFlow<BorderTracker.Status?> = _status.asStateFlow()

    private var tracker: BorderTracker? = null
    private var trackedConfig: FieldConfig? = null
    private var disabled = false
    private var held = false

    init {
        scope.launch {
            while (true) {
                delay(TICK_MILLIS)
                tick()
            }
        }
    }

    /** A GPS fix (accuracy in metres; 0 = unknown). */
    fun onFix(point: LatLon, accuracyMeters: Float) {
        val tracker = currentTracker() ?: return
        handle(tracker.onFix(point, accuracyMeters, clock()), tracker)
    }

    /** Time passing; called about once a second. */
    fun tick() {
        val tracker = currentTracker() ?: return
        handle(tracker.onTick(clock()), tracker)
    }

    /** The tracker for the current border, while a game is on; lifts any penalty otherwise. */
    private fun currentTracker(): BorderTracker? {
        val config = field.value
        val border = config?.border
        if (border == null || !inGame()) {
            clear()
            return null
        }
        if (config != trackedConfig) {
            // A new border or new rules: start over, lifting what the old one imposed
            clear()
            trackedConfig = config
            tracker = BorderTracker(border, config.rules)
        }
        return tracker
    }

    private fun clear() {
        tracker = null
        trackedConfig = null
        _status.value = null
        if (disabled) {
            disabled = false
            penalties.setOutOfBounds(false)
        }
        setHeld(false)
    }

    private fun setHeld(hold: Boolean) {
        if (held == hold) return
        held = hold
        penalties.holdRespawn(hold)
    }

    private fun handle(events: List<BorderTracker.Event>, tracker: BorderTracker) {
        val rules = trackedConfig?.rules ?: return
        for (event in events) {
            when (event) {
                BorderTracker.Event.WARNING_STARTED -> {
                    feedback.vibrate()
                    feedback.play(SessionSound.EMPTY)
                }
                BorderTracker.Event.COUNTDOWN_TICK -> feedback.play(SessionSound.BEEP)
                BorderTracker.Event.PENALTY_STARTED -> {
                    feedback.vibrate()
                    feedback.play(SessionSound.EMPTY)
                    // Every real penalty also holds the blaster until the player is back
                    if (rules.penalty != OutOfBoundsPenalty.WARNING_ONLY) {
                        disabled = true
                        penalties.setOutOfBounds(true)
                    }
                    when (rules.penalty) {
                        OutOfBoundsPenalty.LOSE_HEALTH -> penalties.damage(rules.healthPerTick)
                        OutOfBoundsPenalty.ELIMINATED -> penalties.eliminate()
                        OutOfBoundsPenalty.DISABLE_BLASTER, OutOfBoundsPenalty.WARNING_ONLY -> {}
                    }
                }
                BorderTracker.Event.DAMAGE_TICK -> penalties.damage(rules.healthPerTick)
                BorderTracker.Event.BACK_INSIDE -> {
                    if (disabled) {
                        disabled = false
                        penalties.setOutOfBounds(false)
                    }
                    feedback.play(SessionSound.SPAWN)
                }
            }
        }
        // A player eliminated while outside respawns only once back on the field
        setHeld(rules.penalty != OutOfBoundsPenalty.WARNING_ONLY && tracker.status.phase != BorderTracker.Phase.INSIDE)
        _status.value = tracker.status
    }

    private companion object {
        const val TICK_MILLIS = 1_000L
    }
}
