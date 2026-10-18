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
import com.simplecoil.simplecoil.domain.map.LatLon
import com.simplecoil.simplecoil.domain.session.SessionSound
import com.simplecoil.simplecoil.domain.session.SessionState
import com.simplecoil.simplecoil.domain.session.SpawnPhase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModeControllerTest {

    private val here = LatLon(39.0, -77.0)

    private fun state(owner: Int, capturing: Int = 0, winner: Int = 0) = ModeState(GameMode.DOMINATION,
        listOf(ObjectiveState("B", here, owner, capturing, 0.5, contested = false, active = true, next = false)),
        mapOf(1 to 10), 200, 10.0, winner, 0)

    @Test
    fun itFetchesWhileInGameAndTellsOfPointsChangingHands() = runTest {
        var playing = false
        var reply: ModeState? = state(0)
        val asked = mutableListOf<Boolean>()
        val sounds = mutableListOf<SessionSound>()
        val session = MutableStateFlow(SessionState(health = 10, maxHealth = 10, shotMode = 0, grenadePaired = false))
        val controller = ModeController(
            field = MutableStateFlow(FieldConfig(mode = GameMode.DOMINATION)),
            session = session,
            server = object : ModeController.Server {
                override fun state(playerId: Int, alive: Boolean): ModeState? { asked += alive; return reply }
            },
            me = { 5 to 1 }, inGame = { playing },
            feedback = object : ModeController.Feedback {
                override fun play(sound: SessionSound) { sounds += sound }
                override fun vibrate() {}
            },
            scope = backgroundScope, io = StandardTestDispatcher(testScheduler),
        )
        val notices = mutableListOf<ModeController.Notice>()
        backgroundScope.launch { controller.notices.collect { notices += it } }
        runCurrent()
        assertNull(controller.state.value)
        assertTrue(asked.isEmpty())

        playing = true
        advanceTimeBy(1_100)
        assertEquals(0, controller.state.value!!.objectives[0].owner)

        reply = state(1)
        advanceTimeBy(1_000)
        assertEquals(listOf(ModeController.Notice.Secured("B")), notices)
        assertTrue(SessionSound.SECURED in sounds)

        reply = state(1, capturing = 2)
        advanceTimeBy(1_000)
        assertEquals(ModeController.Notice.UnderAttack("B"), notices.last())

        // Out of the game: the server hears so
        session.value = session.value.copy(spawn = SpawnPhase.Spawning(5, "Bob", false))
        reply = state(2)
        advanceTimeBy(1_000)
        assertEquals(false, asked.last())
        assertEquals(ModeController.Notice.Lost("B"), notices.last())

        // The game ends on the score: one last look shows who won
        playing = false
        reply = state(2, winner = 2)
        advanceTimeBy(1_000)
        assertEquals(2, controller.state.value!!.winner)
    }
}
