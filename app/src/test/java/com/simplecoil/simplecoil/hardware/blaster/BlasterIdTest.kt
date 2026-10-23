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

package com.simplecoil.simplecoil.hardware.blaster

import com.simplecoil.simplecoil.data.repository.GameSettingsRepository
import com.simplecoil.simplecoil.data.repository.PlayerRepository
import org.junit.Assert.assertEquals
import org.junit.Test

/** The app's players 1..64 are the blaster's IR ids 0..63. */
class BlasterIdTest {

    private fun packet(team: Int = 0, hitBy1: Int = 0, sensors1: Int = 0, hitBy2: Int = 0, sensors2: Int = 0) =
        RecoilProtocolFixtures.GOLDEN_IDLE.clone().also {
            it[GattProtocolHandler.RECOIL_OFFSET_TEAM] = team.toByte()
            it[GattProtocolHandler.RECOIL_OFFSET_HIT_BY1] = hitBy1.toByte()
            it[GattProtocolHandler.RECOIL_OFFSET_HIT_SENSORS1] = sensors1.toByte()
            it[GattProtocolHandler.RECOIL_OFFSET_HIT_BY2] = hitBy2.toByte()
            it[GattProtocolHandler.RECOIL_OFFSET_HIT_SENSORS2] = sensors2.toByte()
        }

    @Test
    fun theBlasterIsToldOneLess_andSaysOneLess() {
        assertEquals(0.toByte(), GattProtocolHandler.blasterId(1))
        assertEquals(63.toByte(), GattProtocolHandler.blasterId(GameSettingsRepository.MAX_PLAYER_ID))
        assertEquals(1.toByte(), GattProtocolHandler.parseTelemetry(packet(team = 0))!!.playerId)
        assertEquals(64.toByte(), GattProtocolHandler.parseTelemetry(packet(team = 63))!!.playerId)
    }

    @Test
    fun aHitFromIrIdZero_isPlayerOne_toldFromNoHitByItsSensors() {
        // Nothing in the hit byte and no sensor: no hit
        assertEquals(0, GattProtocolHandler.parseTelemetry(packet())!!.hitByPlayer1)
        // Nothing in the hit byte but a sensor took it: IR id 0, the app's player 1
        val t = GattProtocolHandler.parseTelemetry(packet(sensors1 = 0x02, sensors2 = 0x01))!!
        assertEquals(1 shl 2, t.hitByPlayer1)
        assertEquals(1 shl 2, t.hitByPlayer2)
        // IR id 63 is player 64; still shifted up two bits, as the hit resolver reads it
        assertEquals(64 shl 2, GattProtocolHandler.parseTelemetry(packet(hitBy1 = 63 shl 2, sensors1 = 1))!!.hitByPlayer1)
    }

    @Test
    fun aGrenade_comesThroughAsItIs() {
        val t = GattProtocolHandler.parseTelemetry(packet(hitBy1 = PlayerRepository.GRENADE_PLAYER_ID))!!
        assertEquals(PlayerRepository.GRENADE_PLAYER_ID, t.hitByPlayer1)
    }
}
