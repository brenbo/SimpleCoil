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

package com.simplecoil.simplecoil.network.imagery

import android.util.Log
import com.simplecoil.simplecoil.domain.loadout.DropKind
import com.simplecoil.simplecoil.domain.loadout.LongestShot
import com.simplecoil.simplecoil.domain.loadout.ShotRank
import com.simplecoil.simplecoil.domain.loadout.UavPlayer
import com.simplecoil.simplecoil.domain.map.FieldConfig
import com.simplecoil.simplecoil.domain.mode.ModeState
import org.json.JSONObject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * A player's phone fetching the server's field (map design: Field imagery packs, Map objects):
 * its photo into [store] and its border and rules into [configs]. Nobody has to touch the
 * phone: it asks every few seconds while connected (at most every [minIntervalMillis]), and
 * right away when a game starts.
 */
class FieldImageryClient(
    private val store: FieldPackStore,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val clock: () -> Long,
    private val port: Int = FieldImageryProtocol.PORT,
    private val minIntervalMillis: Long = MIN_INTERVAL_MILLIS,
    private val configs: FieldConfigStore? = null,
) {
    enum class Result { UPDATED, SAME, NONE, FAILED }

    /** The server's clock minus this phone's, from the last config reply. */
    @Volatile
    var serverClockOffsetMillis = 0L

    /** The server's clock now, as best this phone can tell: drops are timed by it. */
    fun serverNow(): Long = System.currentTimeMillis() + serverClockOffsetMillis

    private var running: Job? = null
    private var lastAttempt = Long.MIN_VALUE
    private var lastServer: InetAddress? = null

    /**
     * Asks [server] for its field unless a request is already running, or one went to the same
     * server less than [minIntervalMillis] ago (unless [force]). Main thread.
     */
    fun syncSoon(server: InetAddress?, force: Boolean = false) {
        server ?: return
        if (running?.isActive == true) return
        val now = clock()
        if (!force && server == lastServer && lastAttempt != Long.MIN_VALUE && now - lastAttempt < minIntervalMillis) return
        lastAttempt = now
        lastServer = server
        running = scope.launch(io) { sync(server) }
    }

    /**
     * Fetches [server]'s photo, then its config. [Result.UPDATED] if either changed, [Result.NONE]
     * if the server has neither, [Result.FAILED] if either request failed. Blocks; call it off
     * the main thread.
     */
    fun sync(server: InetAddress): Result {
        val photo = syncPhoto(server)
        val config = if (configs == null) Result.NONE else syncConfig(server, configs)
        return when {
            photo == Result.FAILED || config == Result.FAILED -> Result.FAILED
            photo == Result.UPDATED || config == Result.UPDATED -> Result.UPDATED
            photo == Result.NONE && config == Result.NONE -> Result.NONE
            else -> Result.SAME
        }
    }

    private fun syncPhoto(server: InetAddress): Result {
        val known = store.refresh()?.id
        return request(server, FieldImageryProtocol.Kind.PHOTO, known,
            onNone = { if (known != null) store.clear() },
            onPack = { bytes -> store.save(FieldPackCodec.decode(bytes)) })
    }

    private fun syncConfig(server: InetAddress, configs: FieldConfigStore): Result {
        val known = configs.refresh()?.id
        return request(server, FieldImageryProtocol.Kind.CONFIG, known,
            onNone = { if (known != null) configs.clear() },
            onPack = { bytes -> configs.save(FieldConfig.fromBytes(bytes)) })
    }

    private fun request(
        server: InetAddress,
        kind: FieldImageryProtocol.Kind,
        knownId: String?,
        onNone: () -> Unit,
        onPack: (ByteArray) -> Unit,
    ): Result = try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(server, port), FieldImageryProtocol.CONNECT_TIMEOUT_MS)
            socket.soTimeout = FieldImageryProtocol.READ_TIMEOUT_MS
            val sentAt = System.currentTimeMillis()
            socket.getOutputStream().apply {
                write(FieldImageryProtocol.requestLine(knownId, kind).toByteArray(Charsets.US_ASCII))
                flush()
            }
            val input = BufferedInputStream(socket.getInputStream())
            val line = FieldImageryServer.readLine(input) ?: throw IOException("no reply")
            // How far this phone's clock is from the server's, so drop countdowns agree
            FieldImageryProtocol.serverTime(line)?.let { serverTime ->
                serverClockOffsetMillis = serverTime - (sentAt + System.currentTimeMillis()) / 2
            }
            when (val reply = FieldImageryProtocol.parseReply(line) ?: throw IOException("bad reply: $line")) {
                FieldImageryProtocol.Reply.None -> {
                    // The server has none, so ours is from somewhere else: don't use it here
                    onNone()
                    Result.NONE
                }
                FieldImageryProtocol.Reply.Same -> Result.SAME
                is FieldImageryProtocol.Reply.Pack -> {
                    onPack(readFully(input, reply.length))
                    Log.i(TAG, "Got the field ${kind.name.lowercase()} (${reply.length} bytes)")
                    Result.UPDATED
                }
                else -> throw IOException("unexpected reply: $line")
            }
        }
    } catch (e: IOException) {
        // An older server, or none: the map works without it
        Log.i(TAG, "No field ${kind.name.lowercase()} from $server: " + e.message)
        Result.FAILED
    }

    // Game extras (map design: phase 4): one short request each, off the main thread

    /** Claims drop [dropId] for [playerId]; the kind if this phone got it, else null. */
    fun claim(server: InetAddress, dropId: String, playerId: Int): DropKind? = exchange(server,
        FieldImageryProtocol.claimLine(dropId, playerId)) { reply, _ ->
        (reply as? FieldImageryProtocol.Reply.Granted)?.let { DropKind.fromWire(it.kind) }
    }

    /** Every other player's position, for a UAV; null if the server has none to give. */
    fun uav(server: InetAddress, playerId: Int): List<UavPlayer>? = exchange(server,
        FieldImageryProtocol.requestLine(playerId.toString(), FieldImageryProtocol.Kind.UAV)) { _, body ->
        body?.let { UavPlayer.listFromJson(String(it, Charsets.UTF_8)) }
    }

    /** The game's longest shot; null if there was none (or no server). */
    fun longestShot(server: InetAddress): LongestShot? = exchange(server,
        FieldImageryProtocol.requestLine(null, FieldImageryProtocol.Kind.STATS)) { _, body ->
        body?.let { LongestShot.fromJson(String(it, Charsets.UTF_8)) }
    }

    /** The objective game as it stands, telling the server whether this player is [alive]; null outside one. */
    fun modeState(server: InetAddress, playerId: Int, alive: Boolean): ModeState? = exchange(server,
        FieldImageryProtocol.modeLine(playerId, alive)) { _, body ->
        body?.let { ModeState.fromJson(JSONObject(String(it, Charsets.UTF_8))) }
    }

    /** Every player's longest shot this game, longest first; empty with none yet, null if the server can't say. */
    fun shotRanking(server: InetAddress): List<ShotRank>? = exchange(server,
        FieldImageryProtocol.requestLine(null, FieldImageryProtocol.Kind.STATS)) { reply, body ->
        when {
            body != null -> ShotRank.listFromJson(String(body, Charsets.UTF_8))
            reply == FieldImageryProtocol.Reply.None -> emptyList()
            else -> null
        }
    }

    /** Sends [line], reads the reply (and its body, if it has one) and makes something of them; null on any failure. */
    private fun <T> exchange(server: InetAddress, line: String, read: (FieldImageryProtocol.Reply, ByteArray?) -> T?): T? = try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(server, port), FieldImageryProtocol.CONNECT_TIMEOUT_MS)
            socket.soTimeout = FieldImageryProtocol.READ_TIMEOUT_MS
            socket.getOutputStream().apply {
                write(line.toByteArray(Charsets.US_ASCII))
                flush()
            }
            val input = BufferedInputStream(socket.getInputStream())
            val replyLine = FieldImageryServer.readLine(input) ?: return null
            val reply = FieldImageryProtocol.parseReply(replyLine) ?: return null
            val body = (reply as? FieldImageryProtocol.Reply.Pack)?.let { readFully(input, it.length) }
            read(reply, body)
        }
    } catch (e: Exception) {
        // Including bad JSON: these are extras, never worth a crash
        Log.i(TAG, "Field request failed: " + e.message)
        null
    }

    private fun readFully(input: InputStream, length: Int): ByteArray {
        val bytes = ByteArray(length)
        var read = 0
        while (read < bytes.size) {
            val n = input.read(bytes, read, bytes.size - read)
            if (n < 0) throw IOException("cut short at $read of ${bytes.size}")
            read += n
        }
        return bytes
    }

    companion object {
        private const val TAG = "FieldImageryClient"
        const val MIN_INTERVAL_MILLIS = 5_000L
    }
}
