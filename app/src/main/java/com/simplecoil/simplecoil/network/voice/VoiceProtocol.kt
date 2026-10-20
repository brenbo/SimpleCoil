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

/**
 * Push-to-talk voice (its own UDP port, as the game protocol is frozen). A phone sends 20 ms of
 * 16 kHz mono 16-bit audio per packet to the server, which passes it to whoever may hear it.
 *
 * A packet: "SV" + version, the speaker's player id, flags ([FLAG_EVERYONE]: an admin talking to
 * all), a sequence number (0-255, wrapping), then the samples (little endian).
 */
object VoiceProtocol {
    const val PORT = 17512
    const val SAMPLE_RATE = 16_000
    const val FRAME_MILLIS = 20
    const val SAMPLES_PER_FRAME = SAMPLE_RATE * FRAME_MILLIS / 1000
    const val FRAME_BYTES = SAMPLES_PER_FRAME * 2

    private const val VERSION: Byte = 1
    private const val HEADER = 6
    const val FLAG_EVERYONE = 0x01

    /** Where the flags byte is: the server rewrites it as it passes a packet on. */
    const val FLAGS_OFFSET = 4

    /** The longest a packet can be, header and audio. */
    const val MAX_PACKET = HEADER + FRAME_BYTES

    data class Packet(val playerId: Int, val everyone: Boolean, val seq: Int, val audio: ByteArray) {
        override fun equals(other: Any?): Boolean = other is Packet && playerId == other.playerId &&
            everyone == other.everyone && seq == other.seq && audio.contentEquals(other.audio)
        override fun hashCode(): Int = (playerId * 31 + seq) * 31 + audio.contentHashCode()
    }

    fun encode(packet: Packet): ByteArray = ByteArray(HEADER + packet.audio.size).also { b ->
        b[0] = 'S'.code.toByte()
        b[1] = 'V'.code.toByte()
        b[2] = VERSION
        b[3] = packet.playerId.toByte()
        b[4] = (if (packet.everyone) FLAG_EVERYONE else 0).toByte()
        b[5] = packet.seq.toByte()
        packet.audio.copyInto(b, HEADER)
    }

    /** The packet in the first [length] bytes of [data], or null if it isn't one. */
    fun decode(data: ByteArray, length: Int = data.size): Packet? {
        if (length < HEADER || length > MAX_PACKET) return null
        if (data[0] != 'S'.code.toByte() || data[1] != 'V'.code.toByte() || data[2] != VERSION) return null
        val audio = data.copyOfRange(HEADER, length)
        if (audio.size % 2 != 0) return null
        return Packet(data[3].toInt() and 0xFF, data[4].toInt() and FLAG_EVERYONE != 0, data[5].toInt() and 0xFF, audio)
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
