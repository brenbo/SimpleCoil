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

package com.simplecoil.simplecoil.network.discovery

import android.content.Context
import android.util.Log
import com.simplecoil.simplecoil.network.WireProtocol
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** A server discovered on the LAN. */
data class DiscoveredServer(
    val ip: String,
    val name: String,
    val pingMs: Long,
    val lastSeenMs: Long = android.os.SystemClock.elapsedRealtime(),
    val inGame: Boolean = false,
    val allowJoinInProgress: Boolean = true,
) {
    val canJoin: Boolean get() = !inGame || allowJoinInProgress
}

/**
 * Finds a server on the LAN: listens for beacons on UDP 17500 and broadcasts a query every
 * second until one answers, on its own thread.
 */
class DiscoveryClient(
    context: Context,
    /** This device's address, so its own beacon (if any) is ignored. */
    private val localAddress: () -> InetAddress?,
) {
    interface Listener {
        fun onServerDiscovered(
            serverIp: String,
            serverName: String,
            pingMs: Long,
            inGame: Boolean = false,
            allowJoinInProgress: Boolean = true,
            /** Its certificate's fingerprint (v08); null from an older server. */
            fingerprint: String? = null,
        )
        fun onDiscoveryFailed(errorReason: String?)
    }

    private val network = DiscoveryNetwork(context)

    @Volatile
    private var running = false

    @Volatile
    private var socket: DatagramSocket? = null

    /** Starts looking for a server, unless already looking. [listener] hears on the discovery thread. */
    @Synchronized
    fun start(listener: Listener?) {
        if (running) {
            Log.d(TAG, "Client discovery already running")
            return
        }
        network.acquireMulticastLock()
        running = true
        Thread { run(listener) }.start()
        // And over TCP, for servers whose UDP beacon can't reach us (a Chromebook's)
        sweepWhileRunning(listener)
    }

    private fun run(listener: Listener?) {
        try {
            val s = DiscoveryNetwork.bindDiscoverySocket()
            socket = s
            Log.d(TAG, "Client discovery listening on port ${WireProtocol.DISCOVERY_PORT}")
            var lastQuerySent = android.os.SystemClock.elapsedRealtime()
            sendQuery()
            val packet = DatagramPacket(ByteArray(DiscoveryNetwork.RECEIVE_BUFFER_SIZE), DiscoveryNetwork.RECEIVE_BUFFER_SIZE)
            while (running) {
                try {
                    s.receive(packet)
                    if (packet.address == localAddress()) continue // Ignore self
                    val pingMs = (android.os.SystemClock.elapsedRealtime() - lastQuerySent).coerceAtLeast(1)
                    val msg = String(packet.data, 0, packet.length).trim()
                    val beacon = parseBeacon(msg) ?: continue
                    val serverIp = packet.address.hostAddress ?: continue
                    Log.i(TAG, "Discovered server: ${beacon.name} at $serverIp (ping $pingMs ms, inGame ${beacon.inGame}, allowJoin ${beacon.allowJoin})")
                    listener?.onServerDiscovered(serverIp, beacon.name, pingMs, beacon.inGame, beacon.allowJoin, beacon.fingerprint)
                } catch (ignored: SocketTimeoutException) {
                    // Send query again periodically
                    if (running) {
                        lastQuerySent = android.os.SystemClock.elapsedRealtime()
                        sendQuery()
                    }
                }
            }
        } catch (e: IOException) {
            if (running) {
                Log.e(TAG, "Client discovery thread error", e)
                listener?.onDiscoveryFailed(e.localizedMessage)
            } else {
                Log.d(TAG, "Client discovery socket closed normally")
            }
        } finally {
            socket?.let { if (!it.isClosed) it.close() }
            network.releaseMulticastLock()
            Log.d(TAG, "Client discovery stopped")
        }
    }

    private fun sendQuery() {
        for (address in network.broadcastAddresses()) {
            try {
                network.send(WireProtocol.DISCOVER_MSG, address)
            } catch (e: IOException) {
                Log.e(TAG, "Error sending discovery query to " + address.hostAddress, e)
            }
        }
    }

    /** Asks the server at [address] for its beacon (a typed address, beyond the broadcast). Off the main thread. */
    fun ask(address: InetAddress) {
        Thread {
            try {
                network.send(WireProtocol.DISCOVER_MSG, address)
            } catch (e: IOException) {
                Log.w(TAG, "Could not ask " + address.hostAddress + " for its beacon", e)
            }
        }.start()
    }

    /**
     * The beacon of the server at [address], asked over TCP: what works where UDP doesn't get
     * through (a server on a Chromebook). Null if nothing answers there. Blocks.
     */
    fun fetchBeacon(address: InetAddress, connectTimeoutMs: Int = TCP_CONNECT_TIMEOUT_MS): Beacon? = try {
        Socket().use { s ->
            s.connect(InetSocketAddress(address, WireProtocol.BEACON_TCP_PORT), connectTimeoutMs)
            s.soTimeout = TCP_READ_TIMEOUT_MS
            // The address we used: a Chromebook can't see its own, so it shows this one to its host
            s.getOutputStream().apply {
                write((WireProtocol.DISCOVER_MSG + " " + address.hostAddress + "\n").toByteArray(Charsets.US_ASCII))
                flush()
            }
            BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8)).readLine()?.let { parseBeacon(it.trim()) }
        }
    } catch (e: IOException) {
        null
    }

    /**
     * Every address on this phone's Wi-Fi (its /24) asked over TCP, a few at a time: finds the
     * servers whose UDP beacon can't reach us (a Chromebook's). Each one found goes to [found].
     */
    private fun sweep(found: (String, Beacon, Long) -> Unit) {
        val mine = localAddress() ?: return
        val bytes = mine.address
        if (bytes.size != 4) return
        val pool = Executors.newFixedThreadPool(SWEEP_THREADS)
        try {
            for (last in 1..254) {
                if (last == (bytes[3].toInt() and 0xFF)) continue
                val address = InetAddress.getByAddress(byteArrayOf(bytes[0], bytes[1], bytes[2], last.toByte()))
                pool.execute {
                    if (!running) return@execute
                    val started = android.os.SystemClock.elapsedRealtime()
                    fetchBeacon(address)?.let { beacon ->
                        val ping = (android.os.SystemClock.elapsedRealtime() - started).coerceAtLeast(1)
                        found(address.hostAddress ?: return@let, beacon, ping)
                    }
                }
            }
        } finally {
            pool.shutdown()
            pool.awaitTermination(SWEEP_MAX_MILLIS, TimeUnit.MILLISECONDS)
        }
    }

    /** Sweeps the Wi-Fi for TCP beacons now and every so often, while looking. */
    private fun sweepWhileRunning(listener: Listener?) {
        Thread({
            while (running) {
                sweep { ip, beacon, ping ->
                    if (running) listener?.onServerDiscovered(ip, beacon.name, ping, beacon.inGame, beacon.allowJoin, beacon.fingerprint)
                }
                var waited = 0L
                while (running && waited < SWEEP_INTERVAL_MILLIS) {
                    Thread.sleep(200)
                    waited += 200
                }
            }
        }, "DiscoverySweep").apply { isDaemon = true }.start()
    }

    /** Stops looking. */
    @Synchronized
    fun stop() {
        running = false
        socket?.close()
    }

    /** What a server's beacon says. */
    data class Beacon(val name: String, val inGame: Boolean, val allowJoin: Boolean, val fingerprint: String?)

    companion object {
        private const val TAG = "ServerDiscoveryMgr"
        private const val TCP_CONNECT_TIMEOUT_MS = 400
        private const val TCP_READ_TIMEOUT_MS = 1_500
        private const val SWEEP_THREADS = 32
        private const val SWEEP_INTERVAL_MILLIS = 8_000L
        private const val SWEEP_MAX_MILLIS = 15_000L

        /** A beacon line (UDP or TCP), or null if [msg] isn't one. */
        fun parseBeacon(msg: String): Beacon? {
            val raw = when {
                msg.startsWith(WireProtocol.BEACON_PREFIX) -> msg.substring(WireProtocol.BEACON_PREFIX.length)
                msg.startsWith(WireProtocol.MESSAGE_PREFIX + WireProtocol.NETMSG_SERVERREPLY) -> "SimpleCoil Server"
                else -> return null
            }
            val parts = raw.split("|")
            return Beacon(
                name = parts[0],
                inGame = parts.any { it == "inGame=1" },
                allowJoin = !parts.any { it == "allowJoin=0" },
                fingerprint = parts.firstOrNull { it.startsWith(WireProtocol.BEACON_TLS) }?.removePrefix(WireProtocol.BEACON_TLS),
            )
        }
    }
}
