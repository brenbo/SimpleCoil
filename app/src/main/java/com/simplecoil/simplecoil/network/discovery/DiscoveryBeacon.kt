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
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * The server's discovery beacon: broadcasts the server's name on UDP 17500 every second, and
 * answers every query at once, on its own thread; and answers over TCP 17513 too, where UDP
 * doesn't get through (a Chromebook).
 */
class DiscoveryBeacon(context: Context) {
    private val network = DiscoveryNetwork(context)

    @Volatile
    private var running = false

    @Volatile
    private var socket: DatagramSocket? = null

    @Volatile
    private var currentServerName = "SimpleCoil Server"

    @Volatile
    private var inGame = false

    @Volatile
    private var allowJoinInProgress = true

    /** The server's certificate fingerprint (v08), sent in every beacon. */
    @Volatile
    var fingerprint: String? = null

    /** Updates the server name, game phase and join setting broadcast in beacons. */
    fun updateBeaconState(name: String, inGame: Boolean, allowJoin: Boolean) {
        currentServerName = name
        this.inGame = inGame
        this.allowJoinInProgress = allowJoin
    }

    /** Updates the server name broadcast in beacons. */
    fun updateServerName(name: String) {
        currentServerName = name
    }

    /** Starts broadcasting [serverName], unless the beacon is already on. */
    @Synchronized
    fun start(serverName: String) {
        currentServerName = serverName
        if (running) {
            Log.d(TAG, "Server beacon already running")
            return
        }
        network.acquireMulticastLock()
        running = true
        Thread { run() }.start()
        Thread({ runTcp() }, "BeaconTcpAccept").start()
    }

    private fun run() {
        try {
            val s = DiscoveryNetwork.bindDiscoverySocket()
            socket = s
            Log.d(TAG, "Server discovery beacon started on port ${WireProtocol.DISCOVERY_PORT}")
            val packet = DatagramPacket(ByteArray(DiscoveryNetwork.RECEIVE_BUFFER_SIZE), DiscoveryNetwork.RECEIVE_BUFFER_SIZE)
            var lastBeaconTime = 0L
            while (running) {
                val now = System.currentTimeMillis()
                val name = currentServerName
                if (now - lastBeaconTime >= BEACON_INTERVAL_MS) {
                    for (address in network.broadcastAddresses()) sendBeacon(address, name)
                    lastBeaconTime = now
                }
                try {
                    s.receive(packet)
                    val msg = String(packet.data, 0, packet.length).trim()
                    if (msg.startsWith(WireProtocol.DISCOVER_MSG) || msg.startsWith(WireProtocol.MESSAGE_PREFIX + WireProtocol.NETMSG_JOIN)) {
                        Log.d(TAG, "Received discovery query from " + packet.address.hostAddress)
                        sendBeacon(packet.address, currentServerName)
                    }
                } catch (ignored: SocketTimeoutException) {
                    // Timeout expected to allow periodic beacon loop
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Server beacon thread error", e)
        } finally {
            socket?.let { if (!it.isClosed) it.close() }
            network.releaseMulticastLock()
            Log.d(TAG, "Server discovery beacon stopped")
        }
    }

    private fun sendBeacon(destination: InetAddress, serverName: String) {
        try {
            network.send(payload(serverName), destination)
        } catch (e: IOException) {
            Log.e(TAG, "Error sending discovery beacon packet", e)
        }
    }

    /** The beacon: name, whether a game is on, whether joining one is allowed, the certificate's fingerprint. */
    private fun payload(serverName: String): String =
        WireProtocol.BEACON_PREFIX + serverName +
            "|inGame=" + (if (inGame) 1 else 0) +
            "|allowJoin=" + (if (allowJoinInProgress) 1 else 0) +
            (fingerprint?.let { "|" + WireProtocol.BEACON_TLS + it } ?: "")

    /**
     * Where phones reach this server, as they say when they ask over TCP. A Chromebook can't see
     * its own Wi-Fi address (Android there sits behind ChromeOS's NAT), so it learns it this way.
     */
    @Volatile
    var onReachedAt: ((String) -> Unit)? = null

    @Volatile
    private var tcpSocket: ServerSocket? = null

    /**
     * The beacon over TCP (17513), for phones that can't hear it over UDP: ChromeOS passes TCP
     * through to the app, but not UDP. A phone connects, sends DISCOVER (and the address it used),
     * and gets the beacon back.
     */
    private fun runTcp() {
        val server = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(WireProtocol.BEACON_TCP_PORT))
            }
        } catch (e: IOException) {
            Log.w(TAG, "No TCP beacon: " + e.message)
            return
        }
        tcpSocket = server
        try {
            while (running) {
                val client = server.accept()
                Thread({ answer(client) }, "BeaconTcp").start()
            }
        } catch (e: IOException) {
            if (running) Log.w(TAG, "TCP beacon stopped: " + e.message)
        } finally {
            server.close()
        }
    }

    private fun answer(client: Socket) {
        try {
            client.use {
                it.soTimeout = TCP_READ_TIMEOUT_MS
                val line = BufferedReader(InputStreamReader(it.getInputStream(), Charsets.US_ASCII)).readLine() ?: return
                if (!line.startsWith(WireProtocol.DISCOVER_MSG)) return
                line.removePrefix(WireProtocol.DISCOVER_MSG).trim().takeIf { ip -> IPV4.matches(ip) }?.let { ip -> onReachedAt?.invoke(ip) }
                it.getOutputStream().apply {
                    write((payload(currentServerName) + "\n").toByteArray(Charsets.UTF_8))
                    flush()
                }
            }
        } catch (e: IOException) {
            // A phone that gave up: nothing to do
        }
    }

    /** Stops the beacon. */
    @Synchronized
    fun stop() {
        running = false
        socket?.close()
        try {
            tcpSocket?.close()
        } catch (ignored: IOException) {
        }
        tcpSocket = null
    }

    private companion object {
        const val TAG = "ServerDiscoveryMgr"
        const val BEACON_INTERVAL_MS = 1000
        const val TCP_READ_TIMEOUT_MS = 2_000
        val IPV4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")
    }
}
