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

/**
 * Whether the player is on the field, from their GPS fixes (map design: GPS limits, Border).
 * Built so a few metres of GPS jitter never decides anything on its own:
 *
 * - Fixes worse than the accuracy gate are ignored.
 * - Leaving takes the leave margin past the line; coming back only needs the line.
 * - Outside counts only after the grace time; then the countdown runs; then the penalty.
 *
 * Feed it fixes with [onFix] and time with [onTick] (about once a second). Not thread-safe.
 */
class BorderTracker(private val border: Border, private val rules: BorderRules) {

    enum class Phase {
        /** On the field (or not known yet). */
        INSIDE,

        /** Outside, and the countdown to the penalty is running. */
        WARNING,

        /** Outside past the countdown: the penalty applies. */
        PENALTY,
    }

    /** Where the player stands, and what to tell them. */
    data class Status(
        val phase: Phase,
        /** Seconds until the penalty, while [Phase.WARNING]. */
        val secondsLeft: Int = 0,
        /** How far outside the line, in metres; 0 inside. */
        val metersOutside: Double = 0.0,
        /** How far from the line, in metres, either side; null before any good fix. */
        val metersToEdge: Double? = null,
    )

    /** Sounds and penalties for the caller to carry out, one per change. */
    enum class Event {
        /** The countdown started: warn the player. */
        WARNING_STARTED,

        /** One more second of the countdown went by. */
        COUNTDOWN_TICK,

        /** The countdown ran out: the penalty starts. */
        PENALTY_STARTED,

        /** With the health penalty: take another tick of health. */
        DAMAGE_TICK,

        /** Back on the field: lift the penalty. */
        BACK_INSIDE,
    }

    private var outside = false
    private var outsideSince = Long.MIN_VALUE
    private var lastDamageAt = Long.MIN_VALUE
    private var lastCountdownSecond = Int.MAX_VALUE
    private var metersOutside = 0.0
    private var metersToEdge: Double? = null
    private var phase = Phase.INSIDE

    val status: Status get() = Status(phase, secondsLeft(currentTime), metersOutside, metersToEdge)
    private var currentTime = 0L

    /** A GPS fix with its accuracy (metres, as Android reports it; 0 = unknown). */
    fun onFix(point: LatLon, accuracyMeters: Float, nowMillis: Long): List<Event> {
        currentTime = nowMillis
        if (rules.accuracyGateMeters > 0 && (accuracyMeters <= 0f || accuracyMeters > rules.accuracyGateMeters)) {
            return onTick(nowMillis)
        }
        val inside = border.contains(point)
        val distance = border.distanceToEdgeMeters(point)
        metersToEdge = distance
        metersOutside = if (inside) 0.0 else distance
        val events = ArrayList<Event>()
        if (outside) {
            if (inside) {
                outside = false
                if (phase != Phase.INSIDE) events += Event.BACK_INSIDE
                phase = Phase.INSIDE
            }
        } else if (!inside && distance > rules.leaveMarginMeters) {
            outside = true
            outsideSince = nowMillis
            lastCountdownSecond = Int.MAX_VALUE
        }
        events += onTick(nowMillis)
        return events
    }

    /** Time passing, so the countdown and the health penalty move without a new fix. */
    fun onTick(nowMillis: Long): List<Event> {
        currentTime = nowMillis
        if (!outside) return emptyList()
        val events = ArrayList<Event>()
        val elapsed = nowMillis - outsideSince
        val graceMillis = rules.graceSeconds * 1000L
        val penaltyAt = graceMillis + rules.countdownSeconds * 1000L
        when {
            elapsed < graceMillis -> {}
            elapsed < penaltyAt -> {
                if (phase == Phase.INSIDE) {
                    phase = Phase.WARNING
                    events += Event.WARNING_STARTED
                }
                val left = secondsLeft(nowMillis)
                if (left < lastCountdownSecond) {
                    if (lastCountdownSecond != Int.MAX_VALUE) events += Event.COUNTDOWN_TICK
                    lastCountdownSecond = left
                }
            }
            else -> {
                if (phase != Phase.PENALTY) {
                    if (phase == Phase.INSIDE) events += Event.WARNING_STARTED
                    phase = Phase.PENALTY
                    events += Event.PENALTY_STARTED
                    lastDamageAt = nowMillis
                } else if (rules.penalty == OutOfBoundsPenalty.LOSE_HEALTH &&
                    nowMillis - lastDamageAt >= rules.tickSeconds * 1000L) {
                    lastDamageAt = nowMillis
                    events += Event.DAMAGE_TICK
                }
            }
        }
        return events
    }

    /** Starts the grace time and countdown over, as if the player had just stepped out (after an elimination). */
    fun rearm(nowMillis: Long) {
        if (!outside) return
        outsideSince = nowMillis
        lastCountdownSecond = Int.MAX_VALUE
        phase = Phase.INSIDE
    }

    private fun secondsLeft(nowMillis: Long): Int {
        if (phase != Phase.WARNING) return 0
        val penaltyAt = outsideSince + rules.graceSeconds * 1000L + rules.countdownSeconds * 1000L
        return ((penaltyAt - nowMillis + 999) / 1000).toInt().coerceAtLeast(0)
    }
}
