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

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap

/**
 * The server's voice settings: voice chat on or off for the session (off unless the operator
 * turns it on), and the players who may not talk (they still hear). Served to phones by the
 * field service (`SCFIELD1 VOICE <player id>`), so each knows whether to talk and listen.
 */
class VoiceSettings {
    @Volatile
    var enabled = false

    private val off: MutableSet<Int> = java.util.Collections.newSetFromMap(ConcurrentHashMap())

    /** [playerId] may talk: the session's voice is on and theirs not turned off. */
    fun allowed(playerId: Int): Boolean = enabled && playerId !in off

    /** Whether the operator left talking on for [playerId] (whatever the session's switch). */
    fun allowedFor(playerId: Int): Boolean = playerId !in off

    fun setAllowed(playerId: Int, allowed: Boolean) {
        if (allowed) off -= playerId else off += playerId
    }

    /** The VOICE reply's body for [playerId]: "1" to talk and listen, "L" to listen only, "0" voice is off. */
    fun body(playerId: Int): ByteArray = when {
        !enabled -> "0"
        allowed(playerId) -> "1"
        else -> "L"
    }.toByteArray(Charsets.US_ASCII)
}

/**
 * The server's half of voice chat: takes each phone's audio on [VoiceProtocol.PORT] and passes
 * it to the players [VoiceRules] says hear it, marked as an admin's to everyone only if it is. One thread; packets that can't be
 * passed on are dropped (voice is live: late audio is no use).
 */
class VoiceRelay(
    private val settings: VoiceSettings,
    private val peers: () -> List<VoicePeer>,
    private val freeForAll: () -> Boolean,
    private val port: Int = VoiceProtocol.PORT,
) {
    @Volatile
    private var socket: DatagramSocket? = null

    val localPort: Int get() = socket?.localPort ?: -1

    fun start() {
        if (socket != null) return
        val s = try {
            DatagramSocket(null).apply {
                reuseAddress = true
                // The relay's port, not the unconnected socket's own (-1)
                bind(InetSocketAddress(this@VoiceRelay.port))
            }
        } catch (e: Exception) {
            // No voice chat, but the server runs on
            Log.w(TAG, "No voice relay: " + e.message)
            return
        }
        socket = s
        Thread({ relay(s) }, "VoiceRelay").start()
    }

    fun stop() {
        socket?.close()
        socket = null
    }

    private fun relay(s: DatagramSocket) {
        val buffer = ByteArray(VoiceProtocol.MAX_PACKET)
        val incoming = DatagramPacket(buffer, buffer.size)
        while (!s.isClosed) {
            try {
                incoming.setLength(buffer.size)
                s.receive(incoming)
                val packet = VoiceProtocol.decode(buffer, incoming.length) ?: continue
                val delivery = VoiceRules.recipients(packet.playerId, incoming.address, packet.everyone, settings.enabled,
                    settings::allowed, peers(), freeForAll())
                // Marked "to everyone" only if it really is an admin's, so nobody else gets past a mute
                buffer[VoiceProtocol.FLAGS_OFFSET] = (if (delivery.toEveryone) VoiceProtocol.FLAG_EVERYONE else 0).toByte()
                for (peer in delivery.to) {
                    s.send(DatagramPacket(buffer, incoming.length, peer.address, port))
                }
            } catch (e: Exception) {
                if (!s.isClosed) Log.d(TAG, "Voice packet dropped: " + e.message)
            }
        }
    }

    private companion object {
        const val TAG = "VoiceRelay"
    }
}
