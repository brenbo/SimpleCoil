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
import com.simplecoil.simplecoil.domain.map.FieldConfig
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
            socket.getOutputStream().apply {
                write(FieldImageryProtocol.requestLine(knownId, kind).toByteArray(Charsets.US_ASCII))
                flush()
            }
            val input = BufferedInputStream(socket.getInputStream())
            val line = FieldImageryServer.readLine(input) ?: throw IOException("no reply")
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
            }
        }
    } catch (e: IOException) {
        // An older server, or none: the map works without it
        Log.i(TAG, "No field ${kind.name.lowercase()} from $server: " + e.message)
        Result.FAILED
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
