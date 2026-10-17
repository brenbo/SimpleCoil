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

import com.simplecoil.simplecoil.data.repository.GameSettingsRepository
import com.simplecoil.simplecoil.data.repository.GameStateRepository
import com.simplecoil.simplecoil.domain.session.SessionTestHarness.Packet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The blaster session's loadout: limited ammo, power-ups and extra lives (map design: Ammo, drops and power-ups). */
class LoadoutEngineTest {

    private val h = SessionTestHarness()

    private fun inGame() {
        h.connected()
        h.startedAndSpawned()
    }

    private fun reload() {
        h.telemetry(Packet().shots(10))
        h.telemetry(Packet().shots(10).reload(1))
        h.writeFinished()
        h.advance(10_000)
        h.writeFinished()
    }

    @Test
    fun aReloadUsesAMagazine() {
        inGame()
        h.engine.setAmmoReserve(2)
        reload()
        assertEquals(1, h.state.reserve)
        assertEquals("finishReload:5/30", h.rec.ble.last())
    }

    @Test
    fun noMagazinesNoReload() {
        inGame()
        h.engine.setAmmoReserve(0)
        h.telemetry(Packet().shots(10))
        h.rec.ble.clear()
        h.telemetry(Packet().shots(10).reload(1))
        h.advance(10_000)
        assertTrue(h.rec.ble.isEmpty())
        assertTrue(SessionToast.OUT_OF_AMMO in h.toasts())
    }

    @Test
    fun unlimitedAmmoIsAsBefore() {
        inGame()
        reload()
        assertEquals(null, h.state.reserve)
        assertEquals("finishReload:5/30", h.rec.ble.last())
    }

    @Test
    fun anOvershieldTakesTheHits() {
        h.config.names[2] = "Bob"
        inGame()
        h.engine.addOvershield(1)
        val full = h.state.health
        h.telemetry(Packet().hit1(0x08, 0x3D))
        assertEquals(full, h.state.health)
        assertEquals(0, h.state.overshieldHits)

        h.telemetry(Packet().noHit())
        h.telemetry(Packet().hit1(0x08, 0x3E))
        assertEquals(full - 1, h.state.health)
    }

    @Test
    fun anExtraLifeRespawnsInPlace() {
        h.config.fullHealth = 1
        inGame()
        h.engine.addExtraLife()
        h.telemetry(Packet().hit1(0x08, 0x3D))
        assertEquals(GameStateRepository.GAME_STATE_RUNNING, h.config.gameState)
        assertEquals(SpawnPhase.None, h.state.spawn)
        assertEquals(0, h.state.extraLives)
        assertTrue(SessionToast.EXTRA_LIFE_USED in h.toasts())

        // The next one counts
        h.telemetry(Packet().noHit())
        h.telemetry(Packet().hit1(0x08, 0x3E))
        assertEquals(GameStateRepository.GAME_STATE_ELIMINATED, h.config.gameState)
    }

    @Test
    fun fullAutoFromADropIsLostOnElimination() {
        h.config.fullHealth = 1
        h.config.allowAutoShotMode = false
        inGame()
        h.engine.grantShotMode(GameSettingsRepository.SHOT_MODE_FULL_AUTO)
        assertEquals(GameSettingsRepository.SHOT_MODE_FULL_AUTO, h.state.poweredShotMode)
        assertTrue(h.rec.ble.any { it.startsWith("shotMode:${GameSettingsRepository.SHOT_MODE_FULL_AUTO}/") })

        h.telemetry(Packet().hit1(0x08, 0x3D))
        assertEquals(null, h.state.poweredShotMode)
        // The blaster is set back to a mode the server allows
        assertFalse(h.rec.ble.last { it.startsWith("shotMode:") }.startsWith("shotMode:${GameSettingsRepository.SHOT_MODE_FULL_AUTO}/"))
    }

    @Test
    fun aMedkitHealsFully() {
        h.config.names[2] = "Bob"
        inGame()
        val full = h.state.health
        h.telemetry(Packet().hit1(0x08, 0x3D))
        assertEquals(full - 1, h.state.health)
        h.engine.healFull()
        assertEquals(full, h.state.health)
    }
}
