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

package com.simplecoil.simplecoil.domain.session

import com.simplecoil.simplecoil.data.repository.GameStateRepository
import com.simplecoil.simplecoil.domain.session.SessionTestHarness.Packet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The blaster session's out-of-bounds penalties (map design: Border). */
class OutOfBoundsEngineTest {

    private val h = SessionTestHarness()

    private fun inGame() {
        h.connected()
        h.startedAndSpawned()
    }

    @Test
    fun theBlasterIsHeldUntilThePlayerIsBack() {
        inGame()
        h.engine.setOutOfBounds(true)
        assertEquals(listOf("startReload:5"), h.rec.ble)
        assertEquals(true, h.state.reloadBarShown)

        // No reload timer lets it shoot again
        h.writeFinished()
        h.advance(60_000)
        assertEquals(listOf("startReload:5"), h.rec.ble)

        h.engine.setOutOfBounds(false)
        assertEquals(listOf("startReload:5", "finishReload:5/30"), h.rec.ble)
        h.writeFinished()
        assertEquals(false, h.state.reloadBarShown)
    }

    @Test
    fun theReloadButtonDoesNotGetAroundIt() {
        inGame()
        h.telemetry(Packet().shots(10))
        h.engine.setOutOfBounds(true)
        h.writeFinished()
        h.rec.ble.clear()
        h.telemetry(Packet().shots(10).reload(1))
        h.advance(10_000)
        assertEquals(emptyList<String>(), h.rec.ble)
    }

    @Test
    fun aReloadUnderWayStaysHeld() {
        inGame()
        h.telemetry(Packet().shots(10).reload(1)) // reload starts
        h.writeFinished()
        h.engine.setOutOfBounds(true)
        h.advance(10_000) // the reload time passes
        assertEquals(listOf("startReload:5"), h.rec.ble)
        h.engine.setOutOfBounds(false)
        assertEquals("finishReload:5/30", h.rec.ble.last())
    }

    @Test
    fun respawningOutsideStaysHeld() {
        inGame()
        h.engine.setOutOfBounds(true)
        h.writeFinished()
        h.engine.eliminateOutOfBounds()
        assertEquals(GameStateRepository.GAME_STATE_ELIMINATED, h.config.gameState)
        h.advance((h.config.respawnTimeSeconds + 1) * 1000)
        assertEquals(GameStateRepository.GAME_STATE_RUNNING, h.config.gameState)
        assertFalse(h.rec.ble.any { it.startsWith("finishReload") })

        h.engine.setOutOfBounds(false)
        assertEquals("finishReload:5/30", h.rec.ble.last())
    }

    @Test
    fun healthPenaltyTakesHealthAndCanEliminate() {
        inGame()
        h.rec.useNetwork = true
        val full = h.state.health
        h.engine.applyOutOfBoundsDamage(2)
        assertEquals(full - 2, h.state.health)
        assertTrue(h.rec.net.isEmpty())

        h.engine.applyOutOfBoundsDamage(full)
        assertEquals(GameStateRepository.GAME_STATE_ELIMINATED, h.config.gameState)
        // Reported as eliminated by nobody, so nobody scores
        assertEquals(listOf("ELIMINATED0"), h.rec.net)
    }

    @Test
    fun eliminationPenaltyEliminates() {
        inGame()
        h.rec.useNetwork = true
        h.engine.eliminateOutOfBounds()
        assertEquals(GameStateRepository.GAME_STATE_ELIMINATED, h.config.gameState)
        assertEquals(listOf("ELIMINATED0"), h.rec.net)
    }

    @Test
    fun aHeldRespawnWaitsForThePlayerToComeBack() {
        inGame()
        h.engine.holdRespawn(RespawnHold.OUT_OF_BOUNDS, true)
        h.engine.eliminateOutOfBounds()
        h.advance((h.config.respawnTimeSeconds + 5) * 1000)
        assertTrue(h.state.spawn is SpawnPhase.AwaitingReturn)
        assertEquals(GameStateRepository.GAME_STATE_ELIMINATED, h.config.gameState)

        h.engine.holdRespawn(RespawnHold.OUT_OF_BOUNDS, false)
        assertEquals(SpawnPhase.None, h.state.spawn)
        assertEquals(GameStateRepository.GAME_STATE_RUNNING, h.config.gameState)
        assertEquals("finishReload:5/30", h.rec.ble.last())
    }

    @Test
    fun comingBackDuringTheCountdownRespawnsOnTime() {
        inGame()
        h.engine.holdRespawn(RespawnHold.OUT_OF_BOUNDS, true)
        h.engine.eliminateOutOfBounds()
        h.advance(1_000)
        h.engine.holdRespawn(RespawnHold.OUT_OF_BOUNDS, false)
        assertTrue(h.state.spawn is SpawnPhase.Spawning)
        h.advance(h.config.respawnTimeSeconds * 1000)
        assertEquals(GameStateRepository.GAME_STATE_RUNNING, h.config.gameState)
    }

    @Test
    fun everyHoldMustLetGo() {
        inGame()
        h.engine.holdRespawn(RespawnHold.OUT_OF_BOUNDS, true)
        h.engine.holdRespawn(RespawnHold.AWAY_FROM_SPAWN, true)
        h.engine.eliminateOutOfBounds()
        h.advance((h.config.respawnTimeSeconds + 1) * 1000)
        assertEquals(true, (h.state.spawn as SpawnPhase.AwaitingReturn).outOfBounds)

        // Back on the field, but not at a spawn yet
        h.engine.holdRespawn(RespawnHold.OUT_OF_BOUNDS, false)
        assertEquals(false, (h.state.spawn as SpawnPhase.AwaitingReturn).outOfBounds)
        assertEquals(GameStateRepository.GAME_STATE_ELIMINATED, h.config.gameState)

        h.engine.holdRespawn(RespawnHold.AWAY_FROM_SPAWN, false)
        assertEquals(GameStateRepository.GAME_STATE_RUNNING, h.config.gameState)
    }

    @Test
    fun theShieldCanEndEarly() {
        h.config.respawnShieldTimeSeconds = 10L
        inGame()
        h.engine.eliminateOutOfBounds()
        h.advance((h.config.respawnTimeSeconds + 1) * 1000)
        assertTrue(h.state.shieldSeconds != null)
        h.engine.endRespawnShield()
        assertEquals(null, h.state.shieldSeconds)
    }

    private fun eliminatedWithManualRespawn() {
        h.config.fullHealth = 1
        h.config.manualRespawn = true
        inGame()
        h.telemetry(Packet().hit1(0x08, 0x3D))
        assertTrue(h.state.spawn is SpawnPhase.AwaitingRespawn)
    }

    @Test
    fun holdingReloadWorksEvenIfTheBlasterOnlyReportsThePress() {
        eliminatedWithManualRespawn()
        // One packet when the button goes down, then nothing until it comes up
        h.telemetry(Packet().reload(1).reloadHeld())
        h.advance(BlasterSessionEngine.RELOAD_LONG_PRESS_MILLIS)
        assertTrue(h.state.spawn is SpawnPhase.Spawning)
    }

    @Test
    fun manualRespawnOnlyWorksInTheSpawn() {
        eliminatedWithManualRespawn()
        h.engine.holdRespawn(RespawnHold.AWAY_FROM_SPAWN, true)
        assertEquals(true, (h.state.spawn as SpawnPhase.AwaitingRespawn).needsSpawn)

        // Holding reload away from the spawn does nothing but say so
        h.effects.clear()
        h.telemetry(Packet().reload(1).reloadHeld())
        h.advance(BlasterSessionEngine.RELOAD_LONG_PRESS_MILLIS)
        assertTrue(h.state.spawn is SpawnPhase.AwaitingRespawn)
        assertTrue(h.sounds().contains(SessionSound.EMPTY))
        h.telemetry(Packet().reload(1))

        // In the spawn it works
        h.engine.holdRespawn(RespawnHold.AWAY_FROM_SPAWN, false)
        assertEquals(false, (h.state.spawn as SpawnPhase.AwaitingRespawn).needsSpawn)
        h.telemetry(Packet().reload(2).reloadHeld())
        h.advance(BlasterSessionEngine.RELOAD_LONG_PRESS_MILLIS)
        assertTrue(h.state.spawn is SpawnPhase.Spawning)
    }

    @Test
    fun nothingHappensOutsideAGame() {
        h.connected()
        h.engine.applyOutOfBoundsDamage(3)
        h.engine.eliminateOutOfBounds()
        assertEquals(GameStateRepository.GAME_STATE_NONE, h.config.gameState)
        assertTrue(h.rec.net.isEmpty())
    }

    @Test
    fun endingTheGameClearsIt() {
        inGame()
        h.engine.setOutOfBounds(true)
        h.writeFinished()
        h.engine.endGame()
        assertFalse(h.engine.outOfBounds)
        h.rec.ble.clear()

        // The next game starts with a working blaster
        h.engine.startGame()
        h.runCurrent()
        h.advance(h.config.startCountdownSeconds * 1000)
        assertTrue(h.rec.ble.contains("finishReload:5/30"))
    }
}
