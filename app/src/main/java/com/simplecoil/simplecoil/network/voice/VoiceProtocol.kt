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

import java.net.InetAddress
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Push-to-talk voice (its own UDP port). A phone sends 20 ms of 16 kHz mono 16-bit audio per
 * packet to the server, which passes it to whoever may hear it.
 *
 * A packet (version 2, v08): "SV" + version, the speaker's player id, flags ([FLAG_EVERYONE]: an
 * admin talking to all), a sequence number (0-255, wrapping), a 12-byte random nonce, then the
 * samples (little endian) sealed with AES-GCM under one player's own key, the header as
 * associated data. A phone seals what it says with its key; the server opens it with the
 * speaker's, and seals it again for each listener with theirs (VoiceSettings hands out the keys).
 */
object VoiceProtocol {
    const val PORT = 17512
    const val SAMPLE_RATE = 16_000
    const val FRAME_MILLIS = 20
    const val SAMPLES_PER_FRAME = SAMPLE_RATE * FRAME_MILLIS / 1000
    const val FRAME_BYTES = SAMPLES_PER_FRAME * 2

    private const val VERSION: Byte = 2
    private const val HEADER = 6
    private const val NONCE = 12
    private const val TAG_BYTES = 16
    const val FLAG_EVERYONE = 0x01

    /** A player's key: 256-bit AES. */
    const val KEY_BYTES = 32

    /** The longest a packet can be: header, nonce, audio and tag. */
    const val MAX_PACKET = HEADER + NONCE + FRAME_BYTES + TAG_BYTES

    private val random = SecureRandom()

    data class Packet(val playerId: Int, val everyone: Boolean, val seq: Int, val audio: ByteArray) {
        override fun equals(other: Any?): Boolean = other is Packet && playerId == other.playerId &&
            everyone == other.everyone && seq == other.seq && audio.contentEquals(other.audio)
        override fun hashCode(): Int = (playerId * 31 + seq) * 31 + audio.contentHashCode()
    }

    /** A new random player key. */
    fun newKey(): ByteArray = ByteArray(KEY_BYTES).also { random.nextBytes(it) }

    /** [packet], sealed with [key]. */
    fun seal(packet: Packet, key: ByteArray): ByteArray {
        val header = byteArrayOf('S'.code.toByte(), 'V'.code.toByte(), VERSION, packet.playerId.toByte(),
            (if (packet.everyone) FLAG_EVERYONE else 0).toByte(), packet.seq.toByte())
        val nonce = ByteArray(NONCE).also { random.nextBytes(it) }
        val sealed = cipher(Cipher.ENCRYPT_MODE, key, nonce, header).doFinal(packet.audio)
        return header + nonce + sealed
    }

    /** Whose packet the first [length] bytes of [data] claim to be, before opening it; null if it isn't one. */
    fun speakerOf(data: ByteArray, length: Int = data.size): Int? {
        if (length < HEADER + NONCE + TAG_BYTES || length > MAX_PACKET) return null
        if (data[0] != 'S'.code.toByte() || data[1] != 'V'.code.toByte() || data[2] != VERSION) return null
        return data[3].toInt() and 0xFF
    }

    /** The packet in the first [length] bytes of [data], opened with [key]; null if it isn't one, or wasn't sealed with it. */
    fun open(data: ByteArray, length: Int, key: ByteArray): Packet? {
        speakerOf(data, length) ?: return null
        val header = data.copyOfRange(0, HEADER)
        val nonce = data.copyOfRange(HEADER, HEADER + NONCE)
        val audio = try {
            cipher(Cipher.DECRYPT_MODE, key, nonce, header).doFinal(data, HEADER + NONCE, length - HEADER - NONCE)
        } catch (e: GeneralSecurityException) {
            return null // tampered with, or someone else's key
        }
        if (audio.size % 2 != 0) return null
        return Packet(header[3].toInt() and 0xFF, header[4].toInt() and FLAG_EVERYONE != 0, header[5].toInt() and 0xFF, audio)
    }

    private fun cipher(mode: Int, key: ByteArray, nonce: ByteArray, header: ByteArray): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BYTES * 8, nonce))
            updateAAD(header)
        }

    /** [key] as the VOICE reply carries it: hex (Base64 needs a newer Android than ours). */
    fun keyToText(key: ByteArray): String = key.joinToString("") { "%02x".format(it) }

    /** A key from [text], or null if it isn't one. */
    fun keyFromText(text: String): ByteArray? {
        if (text.length != KEY_BYTES * 2) return null
        return try {
            ByteArray(KEY_BYTES) { i -> text.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        } catch (e: NumberFormatException) {
            null
        }
    }
}

/** A player the server can pass voice to. */
data class VoicePeer(val playerId: Int, val team: Int, val address: InetAddress?, val connected: Boolean, val admin: Boolean)

/** Who may talk, and who hears them (the server's rules). */
object VoiceRules {
    /** Who [speaker] reaches, and whether as an admin talking to everyone (which can't be muted). */
    data class Delivery(val to: List<VoicePeer>, val toEveryone: Boolean)

    /**
     * The players [speaker] reaches: nobody if [mayTalk] says not for them, or they aren't a known
     * player talking from their own address. Their teammates (in free for all, nobody); everyone if
     * they are an admin and asked for [everyone]. With voice chat off ([voiceOn] false), only an
     * admin talking to everyone is heard. Never themselves, nor anyone off the server. A player
     * who may not talk still hears.
     */
    fun recipients(
        speaker: Int,
        from: InetAddress?,
        everyone: Boolean,
        voiceOn: Boolean,
        mayTalk: (Int) -> Boolean,
        peers: List<VoicePeer>,
        freeForAll: Boolean,
    ): Delivery {
        val none = Delivery(emptyList(), false)
        if (!mayTalk(speaker)) return none
        val me = peers.firstOrNull { it.playerId == speaker } ?: return none
        // Only the phone that player is on: no talking as someone else
        if (me.address == null || me.address != from) return none
        val toAll = everyone && me.admin
        if (!voiceOn && !toAll) return none
        return Delivery(peers.filter { p ->
            p.playerId != speaker && p.connected && p.address != null && (toAll || (!freeForAll && p.team == me.team))
        }, toAll)
    }
}

/** What a phone may do with voice chat, as the server says (field service VOICE). */
enum class VoiceAccess {
    /** Voice chat is off on the server (or it can't say). */
    OFF,

    /** Hears teammates (and admins talking to everyone), but may not talk. */
    LISTEN,

    /** Talks and hears. */
    TALK,

    /** Voice chat is off, but this admin may still talk to everyone (and hears the other admins doing it). */
    BROADCAST,

    /** Voice chat is off: hears only an admin talking to everyone. */
    HEAR_BROADCASTS;

    companion object {
        fun fromBody(body: String?): VoiceAccess = when (body) {
            "1" -> TALK
            "L" -> LISTEN
            "A" -> BROADCAST
            "E" -> HEAR_BROADCASTS
            else -> OFF
        }
    }
}

/**
 * What the VOICE reply grants this phone (v08): what it may do, and its own voice key, which it
 * seals what it says with and opens what it hears with. The key comes only to the player's own
 * phone; without one, voice chat is off.
 */
class VoiceGrant(val access: VoiceAccess, val key: ByteArray?) {
    companion object {
        @JvmField
        val OFF = VoiceGrant(VoiceAccess.OFF, null)

        /** The VOICE reply's body: the access letter, then a space and the key in hex. */
        fun fromBody(body: String?): VoiceGrant {
            val parts = body?.split(' ') ?: return OFF
            val key = parts.getOrNull(1)?.let { VoiceProtocol.keyFromText(it) } ?: return OFF
            return VoiceGrant(VoiceAccess.fromBody(parts[0]), key)
        }
    }
}
