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

import com.simplecoil.simplecoil.domain.map.BorderTracker.Event
import com.simplecoil.simplecoil.domain.map.BorderTracker.Phase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BorderTrackerTest {

    private val center = LatLon(39.0, -77.0)
    private val border = Border.Circle(center, 100.0)
    private val rules = BorderRules(countdownSeconds = 10, graceSeconds = 3, leaveMarginMeters = 3.0, accuracyGateMeters = 15.0)

    private fun at(metersFromCenter: Double) = GeoMath.offset(center, metersFromCenter, 0.0)
    private val inside = at(50.0)
    private val outside = at(120.0)

    /** Events over [fromMillis]..[toMillis], one tick a second. */
    private fun BorderTracker.run(fromMillis: Long, toMillis: Long): List<Event> =
        (fromMillis..toMillis step 1000).flatMap { onTick(it) }

    @Test
    fun staysQuietInside() {
        val t = BorderTracker(border, rules)
        assertEquals(emptyList<Event>(), t.onFix(inside, 5f, 0))
        assertEquals(Phase.INSIDE, t.status.phase)
        assertEquals(50.0, t.status.metersToEdge!!, 0.5)
    }

    @Test
    fun graceThenCountdownThenPenalty() {
        val t = BorderTracker(border, rules)
        assertEquals(emptyList<Event>(), t.onFix(outside, 5f, 0))
        assertEquals(Phase.INSIDE, t.status.phase)
        assertEquals(20.0, t.status.metersOutside, 0.5)

        assertEquals(emptyList<Event>(), t.run(1_000, 2_000)) // still in the grace time
        assertEquals(listOf(Event.WARNING_STARTED), t.onTick(3_000))
        assertEquals(Phase.WARNING, t.status.phase)
        assertEquals(10, t.status.secondsLeft)

        val countdown = t.run(4_000, 12_000)
        assertEquals(9, countdown.count { it == Event.COUNTDOWN_TICK })
        assertEquals(1, t.status.secondsLeft)

        assertEquals(listOf(Event.PENALTY_STARTED), t.onTick(13_000))
        assertEquals(Phase.PENALTY, t.status.phase)
        assertEquals(emptyList<Event>(), t.run(14_000, 30_000)) // the disable penalty just stays
    }

    @Test
    fun comingBackLiftsIt() {
        val t = BorderTracker(border, rules)
        t.onFix(outside, 5f, 0)
        t.run(1_000, 14_000)
        assertEquals(listOf(Event.BACK_INSIDE), t.onFix(inside, 5f, 15_000))
        assertEquals(Phase.INSIDE, t.status.phase)
        // And a new trip out starts over, with the full grace time
        t.onFix(outside, 5f, 20_000)
        assertEquals(emptyList<Event>(), t.run(21_000, 22_000))
        assertEquals(listOf(Event.WARNING_STARTED), t.onTick(23_000))
    }

    @Test
    fun backDuringTheGraceTimeIsNothing() {
        val t = BorderTracker(border, rules)
        t.onFix(outside, 5f, 0)
        assertEquals(emptyList<Event>(), t.onFix(inside, 5f, 2_000))
        assertEquals(emptyList<Event>(), t.run(3_000, 20_000))
    }

    @Test
    fun jitterAtTheLineIsIgnored() {
        val t = BorderTracker(border, rules)
        // 2 m past the line is inside the 3 m leave margin
        t.onFix(at(102.0), 5f, 0)
        assertEquals(emptyList<Event>(), t.run(1_000, 30_000))
        assertEquals(Phase.INSIDE, t.status.phase)
    }

    @Test
    fun inaccurateFixesAreIgnored() {
        val t = BorderTracker(border, rules)
        t.onFix(outside, 40f, 0)
        assertEquals(emptyList<Event>(), t.run(1_000, 30_000))
        // An unknown accuracy is ignored too
        t.onFix(outside, 0f, 31_000)
        assertEquals(emptyList<Event>(), t.run(32_000, 60_000))
    }

    @Test
    fun withTheGateOffEveryFixCounts() {
        val t = BorderTracker(border, rules.copy(accuracyGateMeters = 0.0))
        t.onFix(outside, 40f, 0)
        assertTrue(t.run(1_000, 20_000).contains(Event.PENALTY_STARTED))
    }

    @Test
    fun theHealthPenaltyTicks() {
        val t = BorderTracker(border, rules.copy(penalty = OutOfBoundsPenalty.LOSE_HEALTH, tickSeconds = 2))
        t.onFix(outside, 5f, 0)
        val events = t.run(1_000, 19_000)
        assertEquals(1, events.count { it == Event.PENALTY_STARTED })
        // Penalty at 13 s, then a tick at 15, 17 and 19
        assertEquals(3, events.count { it == Event.DAMAGE_TICK })
    }

    @Test
    fun noCountdownGoesStraightToThePenalty() {
        val t = BorderTracker(border, rules.copy(countdownSeconds = 0, graceSeconds = 0))
        assertEquals(listOf(Event.WARNING_STARTED, Event.PENALTY_STARTED), t.onFix(outside, 5f, 0))
    }

    @Test
    fun rearmingStartsTheCountdownOver() {
        val t = BorderTracker(border, rules)
        t.onFix(outside, 5f, 0)
        t.run(1_000, 13_000)
        assertEquals(Phase.PENALTY, t.status.phase)
        t.rearm(14_000)
        assertEquals(Phase.INSIDE, t.status.phase)
        assertEquals(emptyList<Event>(), t.run(15_000, 16_000))
        assertEquals(listOf(Event.WARNING_STARTED), t.onTick(17_000))
    }
}
