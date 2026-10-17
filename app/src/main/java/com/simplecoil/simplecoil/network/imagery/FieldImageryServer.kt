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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * Hands the dedicated server's field to the phones (map design: Field imagery packs, Map
 * objects): its photo, and its config (border and rules). It runs beside the game server on its
 * own port, so the game protocol, which is frozen and caps a frame at 64 KB, never carries
 * them, and old phones simply never ask.
 *
 * One request per connection, one line of ASCII each way:
 * ```
 * phone:  SCFIELD1 GET <the photo id it has, or ->      (the photo)
 *         SCFIELD1 CONFIG <the config id it has, or ->  (the border and rules)
 * server: SCFIELD1 NONE            (the server has none)
 *         SCFIELD1 SAME            (the phone is up to date)
 *         SCFIELD1 PACK <length>   (then that many bytes: FieldPackCodec, or the config's JSON)
 * ```
 */
class FieldImageryServer(
    private val store: FieldPackStore,
    private val configs: FieldConfigStore? = null,
    private val port: Int = FieldImageryProtocol.PORT,
) {
    @Volatile
    private var socket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private var workers: ExecutorService? = null

    /** What each phone (by address) last got or confirmed: photo id and config id. */
    private val delivered = HashMap<String, Pair<String?, String?>>()
    private val _deliveredCount = MutableStateFlow(0)

    /** How many phones have the server's current field, photo and config both. */
    val deliveredCount: StateFlow<Int> = _deliveredCount.asStateFlow()

    /** The port actually listened on (useful when [port] is 0, in tests); -1 while stopped. */
    val localPort: Int get() = socket?.localPort ?: -1

    @Synchronized
    fun start() {
        if (socket != null) return
        val server = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(port))
            }
        } catch (e: IOException) {
            Log.w(TAG, "Field imagery server could not listen on $port: " + e.message)
            return
        }
        socket = server
        val pool = Executors.newFixedThreadPool(MAX_CONCURRENT_SENDS)
        workers = pool
        acceptThread = Thread({ acceptLoop(server, pool) }, "FieldImageryServer").apply {
            isDaemon = true
            start()
        }
    }

    @Synchronized
    fun stop() {
        try {
            socket?.close()
        } catch (_: IOException) {
        }
        socket = null
        workers?.shutdownNow()
        workers = null
        acceptThread = null
    }

    /**
     * The config served in place of the host's saved one while a game moves the spawns (map
     * design: Moving spawns); null serves the saved one again. The saved setup is never changed.
     */
    @Volatile
    var liveConfig: FieldConfig? = null
        set(value) {
            field = value
            onFieldChanged()
        }

    /** The field changed (photo, border or rules): count who has it again. */
    fun onFieldChanged() {
        synchronized(delivered) { recount() }
    }

    private fun acceptLoop(server: ServerSocket, pool: ExecutorService) {
        while (!server.isClosed) {
            val client = try {
                server.accept()
            } catch (e: SocketException) {
                break // closed by stop()
            } catch (e: IOException) {
                Log.w(TAG, "accept failed: " + e.message)
                continue
            }
            try {
                pool.execute { serve(client) }
            } catch (e: RejectedExecutionException) {
                close(client)
            }
        }
    }

    private fun serve(client: Socket) {
        try {
            client.soTimeout = FieldImageryProtocol.READ_TIMEOUT_MS
            client.tcpNoDelay = true
            val line = readLine(client.getInputStream()) ?: return
            val request = FieldImageryProtocol.parseRequest(line) ?: return
            val out = BufferedOutputStream(client.getOutputStream())
            val address = client.inetAddress?.hostAddress ?: "?"
            val currentId: String?
            val bytes: ByteArray?
            when (request.kind) {
                FieldImageryProtocol.Kind.PHOTO -> {
                    currentId = store.refresh()?.id
                    bytes = if (currentId == null || currentId == request.knownId) null else store.bytes()
                }
                FieldImageryProtocol.Kind.CONFIG -> {
                    val config = liveConfig ?: configs?.refresh()
                    currentId = config?.id
                    bytes = if (config == null || currentId == request.knownId) null else config.toBytes()
                }
            }
            when {
                currentId != null && currentId == request.knownId -> out.write(FieldImageryProtocol.SAME_LINE.toByteArray(Charsets.US_ASCII))
                currentId == null || bytes == null -> out.write(FieldImageryProtocol.NONE_LINE.toByteArray(Charsets.US_ASCII))
                else -> {
                    out.write(FieldImageryProtocol.packLine(bytes.size).toByteArray(Charsets.US_ASCII))
                    out.write(bytes)
                }
            }
            out.flush()
            markDelivered(address, request.kind, currentId)
        } catch (e: IOException) {
            Log.i(TAG, "Field request failed: " + e.message)
        } finally {
            close(client)
        }
    }

    private fun markDelivered(address: String, kind: FieldImageryProtocol.Kind, id: String?) {
        synchronized(delivered) {
            val (photo, config) = delivered[address] ?: (null to null)
            delivered[address] = when (kind) {
                FieldImageryProtocol.Kind.PHOTO -> id to config
                FieldImageryProtocol.Kind.CONFIG -> photo to id
            }
            recount()
        }
    }

    /** Under the [delivered] lock. */
    private fun recount() {
        val photo = store.info.value?.id
        val config = (liveConfig ?: configs?.config?.value)?.id
        _deliveredCount.value = if (photo == null && config == null) 0 else {
            delivered.values.count { (p, c) -> p == photo && c == config }
        }
    }

    private fun close(client: Socket) {
        try {
            client.close()
        } catch (_: IOException) {
        }
    }

    companion object {
        private const val TAG = "FieldImageryServer"

        /** 20 phones joining at once take turns, a few at a time, so Wi-Fi isn't swamped. */
        const val MAX_CONCURRENT_SENDS = 4

        /** One ASCII line, without its newline; null at the end of the stream or past [maxLength]. */
        @Throws(IOException::class)
        internal fun readLine(input: InputStream, maxLength: Int = FieldImageryProtocol.MAX_LINE_LENGTH): String? {
            val sb = StringBuilder()
            while (true) {
                val b = input.read()
                if (b < 0) return null
                if (b == '\n'.code) return sb.toString().trimEnd('\r')
                if (sb.length >= maxLength) return null
                sb.append(b.toChar())
            }
        }
    }
}

/** The field service's wire format; see [FieldImageryServer]. */
object FieldImageryProtocol {
    /** Beside the game server's 17510. */
    const val PORT = 17511

    const val VERSION = "SCFIELD1"
    const val NONE_LINE = "$VERSION NONE\n"
    const val SAME_LINE = "$VERSION SAME\n"
    private const val PACK = "PACK"

    /** Sent for "none yet". */
    const val NO_ID = "-"

    const val MAX_LINE_LENGTH = 200
    const val CONNECT_TIMEOUT_MS = 3_000
    const val READ_TIMEOUT_MS = 15_000

    /** What a phone asks for. */
    enum class Kind(val verb: String) { PHOTO("GET"), CONFIG("CONFIG") }

    /** A phone's request: [kind], and the id it already has (`""` for none). */
    data class Request(val kind: Kind, val knownId: String)

    fun requestLine(knownId: String?, kind: Kind = Kind.PHOTO): String =
        "$VERSION ${kind.verb} ${knownId?.takeIf { it.isNotEmpty() } ?: NO_ID}\n"

    fun packLine(length: Int): String = "$VERSION $PACK $length\n"

    /** The request a line holds, or null if it isn't one. */
    fun parseRequest(line: String): Request? {
        val parts = line.trim().split(' ')
        if (parts.size != 3 || parts[0] != VERSION) return null
        val kind = Kind.entries.firstOrNull { it.verb == parts[1] } ?: return null
        return Request(kind, if (parts[2] == NO_ID) "" else parts[2])
    }

    /** The server's answer. */
    sealed interface Reply {
        data object None : Reply
        data object Same : Reply
        data class Pack(val length: Int) : Reply
    }

    /** The reply a server's line means, or null if it is garbage. */
    fun parseReply(line: String): Reply? {
        val parts = line.trim().split(' ')
        if (parts.isEmpty() || parts[0] != VERSION) return null
        return when {
            parts.size == 2 && parts[1] == "NONE" -> Reply.None
            parts.size == 2 && parts[1] == "SAME" -> Reply.Same
            parts.size == 3 && parts[1] == PACK -> parts[2].toIntOrNull()
                ?.takeIf { it in 1..FieldPackCodec.MAX_PACK_BYTES }?.let { Reply.Pack(it) }
            else -> null
        }
    }
}
