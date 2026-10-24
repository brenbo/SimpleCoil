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

package com.simplecoil.simplecoil.network.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class VoiceTest {

    private fun ip(last: Int) = InetAddress.getByAddress(byteArrayOf(192.toByte(), 168.toByte(), 1, last.toByte()))

    /** Players 1, 2 on team 1; 3, 4 on team 2; 4 is an admin; 5 (team 1) is gone. */
    private val peers = listOf(
        VoicePeer(1, 1, ip(1), connected = true, admin = false),
        VoicePeer(2, 1, ip(2), connected = true, admin = false),
        VoicePeer(3, 2, ip(3), connected = true, admin = false),
        VoicePeer(4, 2, ip(4), connected = true, admin = true),
        VoicePeer(5, 1, ip(5), connected = false, admin = false),
    )

    private fun heard(speaker: Int, everyone: Boolean = false, voiceOn: Boolean = true, off: Set<Int> = emptySet(),
                      from: InetAddress? = ip(speaker), freeForAll: Boolean = false) =
        VoiceRules.recipients(speaker, from, everyone, voiceOn, { it !in off }, peers, freeForAll).to.map { it.playerId }

    @Test
    fun onlyTeammatesHear() {
        assertEquals(listOf(2), heard(1))
        assertEquals(listOf(4), heard(3))
    }

    @Test
    fun anAdminCanTalkToEveryone_othersCant() {
        assertEquals(listOf(1, 2, 3), heard(4, everyone = true))
        assertEquals(listOf(2), heard(1, everyone = true))
    }

    @Test
    fun nobodyWhenVoiceIsOff_orTheyMayNotTalk_orTalkAsSomeoneElse() {
        assertTrue(heard(1, voiceOn = false).isEmpty())
        assertTrue(heard(1, off = setOf(1)).isEmpty())
        // A player who may not talk still hears
        assertEquals(listOf(2), heard(1, off = setOf(2)))
        assertTrue(heard(1, from = ip(9)).isEmpty())
    }

    @Test
    fun withVoiceOff_anAdminCanStillTalkToEveryone_butNotToTheirTeam() {
        assertEquals(listOf(1, 2, 3), heard(4, everyone = true, voiceOn = false))
        assertTrue(heard(4, voiceOn = false).isEmpty())
        // Not when the operator turned their talking off
        assertTrue(heard(4, everyone = true, voiceOn = false, off = setOf(4)).isEmpty())
    }

    @Test
    fun onlyAnAdminTalkingToEveryoneIsMarkedSo_soNobodyElseGetsPastAMute() {
        val ask = { speaker: Int -> VoiceRules.recipients(speaker, ip(speaker), true, true, { true }, peers, false) }
        assertTrue(ask(4).toEveryone)
        assertFalse(ask(1).toEveryone)
        assertFalse(VoiceRules.recipients(4, ip(4), false, true, { true }, peers, false).toEveryone)
    }

    @Test
    fun freeForAllHasNoTeammates() {
        assertTrue(heard(1, freeForAll = true).isEmpty())
        assertEquals(listOf(1, 2, 3), heard(4, everyone = true, freeForAll = true))
    }

    @Test
    fun aPacketIsSealed_andOpensOnlyWithItsKey_unchanged() {
        val audio = ByteArray(VoiceProtocol.FRAME_BYTES) { it.toByte() }
        val packet = VoiceProtocol.Packet(17, everyone = true, seq = 200, audio = audio)
        val key = VoiceProtocol.newKey()
        val sealed = VoiceProtocol.seal(packet, key)
        assertEquals(sealed.size, VoiceProtocol.MAX_PACKET)
        assertEquals(17, VoiceProtocol.speakerOf(sealed))
        assertEquals(packet, VoiceProtocol.open(sealed, sealed.size, key))
        // The audio isn't in the clear
        assertFalse(sealed.toList().windowed(audio.size).any { it == audio.toList() })
        // Someone else's key, or a changed byte (the header too, so "to everyone" can't be faked): nothing
        assertNull(VoiceProtocol.open(sealed, sealed.size, VoiceProtocol.newKey()))
        assertNull(VoiceProtocol.open(sealed.copyOf().also { it[4] = 0 }, sealed.size, key))
        assertNull(VoiceProtocol.open(sealed.copyOf().also { it[100] = (it[100] + 1).toByte() }, sealed.size, key))
        // Junk isn't a packet
        assertNull(VoiceProtocol.speakerOf("hello".toByteArray()))
        assertNull(VoiceProtocol.speakerOf(ByteArray(VoiceProtocol.MAX_PACKET + 10)))
    }

    @Test
    fun aVoiceKey_goesOnlyToThePlayersOwnPhone() {
        val settings = VoiceSettings().apply { enabled = true }
        val phone = java.net.InetAddress.getByName("192.168.1.20")
        val other = java.net.InetAddress.getByName("192.168.1.66")
        settings.addressOf = { if (it == 2) phone else null }
        // Asked from somewhere else: the access, but no key
        assertEquals("1", String(settings.body(2, other)))
        assertNull(settings.keyOf(2))
        assertEquals(VoiceAccess.OFF, VoiceGrant.fromBody("1").access)
        // From the player's phone: the key, the same one each time
        val grant = VoiceGrant.fromBody(String(settings.body(2, phone)))
        assertEquals(VoiceAccess.TALK, grant.access)
        assertTrue(grant.key!!.contentEquals(settings.keyOf(2)!!))
        assertTrue(VoiceGrant.fromBody(String(settings.body(2, phone))).key!!.contentEquals(grant.key!!))
        settings.forgetKeys()
        assertNull(settings.keyOf(2))
    }

    @Test
    fun theRelayStartsOnItsPort_andStops() {
        // Any free port (0): it binds there, not to the unconnected socket's -1
        val relay = VoiceRelay(VoiceSettings(), { peers }, { false }, port = 0)
        relay.start()
        assertTrue(relay.localPort > 0)
        relay.stop()
        assertEquals(-1, relay.localPort)
    }

    @Test
    fun theServersVoiceSettings_offUntilTurnedOn() {
        val settings = VoiceSettings()
        assertFalse(settings.allowed(1))
        settings.enabled = true
        assertTrue(settings.allowed(1))
        settings.setAllowed(1, false)
        assertFalse(settings.allowed(1))
        assertFalse(settings.allowedFor(1))
        // Turned off for one player: they still listen
        assertEquals("L", String(settings.body(1, null)))
        assertEquals("1", String(settings.body(2, null)))
        settings.enabled = false
        // Voice chat off: everyone listens for an admin talking to everyone, and an admin may
        assertEquals("E", String(settings.body(2, null)))
        settings.isAdmin = { it == 2 }
        assertEquals("A", String(settings.body(2, null)))
        assertEquals("E", String(settings.body(1, null)))
        assertEquals(VoiceAccess.BROADCAST, VoiceAccess.fromBody("A"))
        assertEquals(VoiceAccess.HEAR_BROADCASTS, VoiceAccess.fromBody("E"))
        assertEquals(VoiceAccess.LISTEN, VoiceAccess.fromBody("L"))
        assertEquals(VoiceAccess.OFF, VoiceAccess.fromBody(null))
    }
}
