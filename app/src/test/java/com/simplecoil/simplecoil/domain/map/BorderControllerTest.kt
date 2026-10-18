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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BorderControllerTest {

    private val center = LatLon(39.0, -77.0)
    private val inside = GeoMath.offset(center, 50.0, 0.0)
    private val outside = GeoMath.offset(center, 130.0, 0.0)

    private val field = MutableStateFlow<FieldConfig?>(FieldConfig(border = Border.Circle(center, 100.0),
        rules = BorderRules(countdownSeconds = 5, graceSeconds = 2)))
    private var inGame = true
    private val calls = mutableListOf<String>()
    private val scope = TestScope()

    private val controller = BorderController(
        field = field,
        penalties = object : BorderController.Penalties {
            override fun setOutOfBounds(disabled: Boolean) { calls += "disabled:$disabled" }
            override fun holdRespawn(held: Boolean) { calls += "held:$held" }
            override fun damage(amount: Int) { calls += "damage:$amount" }
            override fun eliminate() { calls += "eliminate" }
        },
        inGame = { inGame },
        feedback = object : BorderController.Feedback {
            override fun play(sound: SessionSound) { calls += "sound:$sound" }
            override fun vibrate() { calls += "vibrate" }
        },
        scope = scope,
        clock = { scope.testScheduler.currentTime },
    )

    /** Runs the controller's own once-a-second ticker for [millis]. */
    private fun advance(millis: Long) {
        scope.testScheduler.advanceTimeBy(millis)
        scope.testScheduler.runCurrent()
    }

    @Test
    fun warnsThenDisablesThenLifts() {
        controller.onFix(outside, 5f)
        advance(2_000)
        assertTrue(calls.containsAll(listOf("vibrate", "sound:OUT_OF_BOUNDS")))
        assertEquals(BorderTracker.Phase.WARNING, controller.status.value!!.phase)
        calls.clear()

        advance(5_000)
        assertTrue("got $calls", calls.contains("disabled:true"))
        assertEquals(4, calls.count { it == "sound:BEEP" })
        calls.clear()

        controller.onFix(inside, 5f)
        assertEquals(listOf("disabled:false", "sound:BACK_IN_BOUNDS", "held:false"), calls)
        assertEquals(BorderTracker.Phase.INSIDE, controller.status.value!!.phase)
    }

    @Test
    fun healthPenaltyTakesHealthEachTick() {
        field.value = field.value!!.copy(rules = field.value!!.rules.copy(penalty = OutOfBoundsPenalty.LOSE_HEALTH, healthPerTick = 2, tickSeconds = 1))
        controller.onFix(outside, 5f)
        advance(10_000)
        // Penalty at 7 s, then at 8, 9 and 10 s
        assertEquals(4, calls.count { it == "damage:2" })
        // And the blaster is off while it lasts
        assertEquals(1, calls.count { it == "disabled:true" })
        controller.onFix(inside, 5f)
        assertTrue(calls.contains("disabled:false"))
    }

    @Test
    fun eliminationPenaltyEliminatesOnceAndHoldsTheRespawn() {
        field.value = field.value!!.copy(rules = field.value!!.rules.copy(penalty = OutOfBoundsPenalty.ELIMINATED))
        controller.onFix(outside, 5f)
        advance(7_000)
        assertEquals(1, calls.count { it == "eliminate" })
        assertTrue(calls.contains("disabled:true"))
        // Staying outside doesn't eliminate again; the respawn just waits
        advance(30_000)
        assertEquals(1, calls.count { it == "eliminate" })
        assertEquals("held:true", calls.last { it.startsWith("held") })

        controller.onFix(inside, 5f)
        assertEquals("held:false", calls.last { it.startsWith("held") })
        assertTrue(calls.indexOf("disabled:false") < calls.lastIndexOf("held:false"))
    }

    @Test
    fun theRespawnIsHeldFromTheWarningOn() {
        controller.onFix(outside, 5f)
        advance(1_000) // grace: not held yet
        assertTrue(calls.none { it.startsWith("held") })
        advance(1_000) // the warning starts
        assertEquals("held:true", calls.last { it.startsWith("held") })
    }

    @Test
    fun warningOnlyHoldsNothing() {
        field.value = field.value!!.copy(rules = field.value!!.rules.copy(penalty = OutOfBoundsPenalty.WARNING_ONLY))
        controller.onFix(outside, 5f)
        advance(20_000)
        assertTrue(calls.none { it.startsWith("held:true") || it.startsWith("disabled:true") })
    }

    @Test
    fun theGameEndingLiftsThePenalty() {
        controller.onFix(outside, 5f)
        advance(8_000)
        assertTrue(calls.contains("disabled:true"))
        inGame = false
        advance(1_000)
        assertEquals(listOf("disabled:false", "held:false"), calls.takeLast(2))
        assertNull(controller.status.value)
    }

    @Test
    fun aNewBorderStartsOver() {
        controller.onFix(outside, 5f)
        advance(8_000)
        // The host moves the border out to where the player stands
        field.value = FieldConfig(border = Border.Circle(center, 200.0), rules = field.value!!.rules)
        controller.onFix(outside, 5f)
        assertTrue(calls.contains("disabled:false"))
        assertEquals(BorderTracker.Phase.INSIDE, controller.status.value!!.phase)
    }

    @Test
    fun noBorderNoChecks() {
        field.value = FieldConfig()
        controller.onFix(outside, 5f)
        advance(20_000)
        assertEquals(emptyList<String>(), calls)
        assertNull(controller.status.value)
    }

    @Test
    fun outsideAGameNothingHappens() {
        inGame = false
        controller.onFix(outside, 5f)
        advance(20_000)
        assertEquals(emptyList<String>(), calls)
    }
}
