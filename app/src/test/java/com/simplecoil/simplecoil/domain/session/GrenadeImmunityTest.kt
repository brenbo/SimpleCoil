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
import org.junit.Assert.assertTrue
import org.junit.Test

/** A grenade that eliminated a player can't eliminate them again for a while; other grenades can. */
class GrenadeImmunityTest {

    private val h = SessionTestHarness()

    /** Grenade [id]'s damage. */
    private fun grenade(id: Int) = Packet().hit1(167, (id shl 4) or 0x01)

    private fun hitBy(packet: Packet) {
        h.telemetry(Packet().noHit())
        h.telemetry(packet)
    }

    /** Back in after the respawn countdown and the respawn shield. */
    private fun respawn() {
        h.advance(RESPAWN_AND_SHIELD_MILLIS)
        assertEquals(GameStateRepository.GAME_STATE_RUNNING, h.config.gameState)
    }

    @Test
    fun whileHitsArePaused_nothingEliminates() {
        // A Search & Destroy round waiting for everyone at their spawn
        h.config.fullHealth = 1
        h.connected()
        h.startedAndSpawned()
        h.rec.ble.clear()
        h.engine.setHitsPaused(true)
        // The blaster is held, as if reloading: it can't shoot either
        assertTrue(h.rec.ble.any { it.startsWith("startReload") })
        hitBy(Packet().hit1(5, 0x01))
        hitBy(grenade(3))
        assertEquals(GameStateRepository.GAME_STATE_RUNNING, h.config.gameState)
        h.engine.setHitsPaused(false)
        h.telemetry(Packet().noHit())
        assertTrue(h.rec.ble.any { it.startsWith("finishReload") })
        hitBy(Packet().hit1(5, 0x01))
        assertEquals(GameStateRepository.GAME_STATE_ELIMINATED, h.config.gameState)
    }

    @Test
    fun aLateJoinerSitsOut_untilTheNextRoundRevivesThem() {
        h.config.fullHealth = 1
        h.connected()
        h.startedAndSpawned()
        h.engine.sitOut(untilNextRound = true)
        assertEquals(SpawnPhase.SittingOut(true), h.state.spawn)
        // Out: hits do nothing to them, and there's no elimination to report
        hitBy(Packet().hit1(5, 0x01))
        assertEquals(0, eliminatedCount())
        h.engine.revive()
        assertTrue(h.state.spawn is SpawnPhase.Spawning)
    }

    @Test
    fun withVoiceChatOn_holdingTheThumbButtonTalks_aTapStillSwitchesTheShotMode() {
        h.connected()
        h.startedAndSpawned()
        h.engine.pushToTalk = true
        val effects = h.effects
        // Held: talking starts after a moment, even with no packets while it's held
        h.telemetry(Packet().noHit().thumb(1).thumbHeld())
        h.advance(BlasterSessionEngine.PUSH_TO_TALK_MILLIS + 50)
        assertTrue(SessionEffect.TalkStarted in effects)
        val mode = h.engine.shotMode
        h.telemetry(Packet().noHit().thumb(1))
        assertTrue(SessionEffect.TalkStopped in effects)
        assertEquals(mode, h.engine.shotMode)
        // A quick tap: no talking
        effects.clear()
        h.telemetry(Packet().noHit().thumb(2).thumbHeld())
        h.advance(50)
        h.telemetry(Packet().noHit().thumb(2))
        h.advance(BlasterSessionEngine.PUSH_TO_TALK_MILLIS)
        assertTrue(SessionEffect.TalkStarted !in effects)
    }

    @Test
    fun theSameGrenadeCannotEliminateAgainForAWhile() {
        h.config.fullHealth = 1
        h.connected()
        h.startedAndSpawned()
        hitBy(grenade(3))
        assertEquals(GameStateRepository.GAME_STATE_ELIMINATED, h.config.gameState)
        respawn()

        // Grenade 3 again: nothing
        hitBy(grenade(3))
        assertEquals(GameStateRepository.GAME_STATE_RUNNING, h.config.gameState)

        // Another grenade still counts
        hitBy(grenade(4))
        assertEquals(GameStateRepository.GAME_STATE_ELIMINATED, h.config.gameState)
        respawn()

        // Grenade 4 is now the one ignored; grenade 3 counts again
        hitBy(grenade(4))
        assertEquals(GameStateRepository.GAME_STATE_RUNNING, h.config.gameState)
        hitBy(grenade(3))
        assertEquals(GameStateRepository.GAME_STATE_ELIMINATED, h.config.gameState)
    }

    @Test
    fun afterAMinuteAndAHalfItCountsAgain() {
        h.config.fullHealth = 1
        h.connected()
        h.startedAndSpawned()
        hitBy(grenade(3))
        respawn()
        h.advance(BlasterSessionEngine.GRENADE_IMMUNITY_MILLIS)
        hitBy(grenade(3))
        assertEquals(GameStateRepository.GAME_STATE_ELIMINATED, h.config.gameState)
    }

    @Test
    fun eachEliminationStartsTheWaitAgain_inANetworkGame() {
        h.rec.useNetwork = true
        eachEliminationStartsTheWaitAgain()
    }

    @Test
    fun eachEliminationStartsTheWaitAgain() {
        h.config.fullHealth = 1
        h.connected()
        h.startedAndSpawned()
        hitBy(grenade(3))
        respawn()
        h.advance(BlasterSessionEngine.GRENADE_IMMUNITY_MILLIS)
        // The same grenade, set off again a minute and a half later: it eliminates once more...
        hitBy(grenade(3))
        assertEquals(GameStateRepository.GAME_STATE_ELIMINATED, h.config.gameState)
        respawn()
        // ...and then can't again for another minute and a half
        hitBy(grenade(3))
        assertEquals(GameStateRepository.GAME_STATE_RUNNING, h.config.gameState)
    }

    /** A grenade going off for [seconds]: its damage in every packet, ten packets a second. */
    private fun blast(id: Int, seconds: Int) {
        repeat(seconds * 10) {
            h.telemetry(grenade(id))
            h.advance(100)
        }
        h.telemetry(Packet().noHit())
    }

    private fun eliminatedCount() = h.sounds().count { it == SessionSound.ELIMINATED }

    @Test
    fun oneBlastEliminatesOnce_firstTimeAndAfterTheWait() {
        // A quick respawn: back in while the grenade is still going off
        h.config.fullHealth = 3
        h.config.respawnTimeSeconds = 2
        h.config.respawnShieldTimeSeconds = 0
        h.rec.useNetwork = true
        h.connected()
        h.startedAndSpawned()
        blast(3, 15)
        assertEquals(1, eliminatedCount())
        h.advance(BlasterSessionEngine.GRENADE_IMMUNITY_MILLIS)
        blast(3, 15)
        assertEquals(2, eliminatedCount())
    }

    @Test
    fun grenadeZeroIsAGrenadeToo() {
        // A grenade's id can be 0: that one is ignored after it eliminates you, like any other
        h.config.fullHealth = 3
        h.config.respawnTimeSeconds = 2
        h.config.respawnShieldTimeSeconds = 0
        h.rec.useNetwork = true
        h.connected()
        h.startedAndSpawned()
        blast(0, 15)
        assertEquals(1, eliminatedCount())
    }

    private companion object {
        const val RESPAWN_AND_SHIELD_MILLIS = 30_000L
    }
}
