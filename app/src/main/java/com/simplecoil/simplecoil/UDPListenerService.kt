/*
 * Copyright (C) 2018 Ethan Yonker
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

package com.simplecoil.simplecoil

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import com.simplecoil.simplecoil.data.repository.GameStateRepository
import com.simplecoil.simplecoil.data.repository.PlayerRepository
import com.simplecoil.simplecoil.network.ClientEvent
import com.simplecoil.simplecoil.network.EventBus
import com.simplecoil.simplecoil.network.discovery.DiscoveryClient
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * LAN server discovery for the play screen, as a service: the [DiscoveryClient] that finds a
 * server. The dedicated server's beacon lives in TcpServer. The game itself runs over TCP.
 */
class UDPListenerService : Service() {

    private lateinit var gameState: GameStateRepository
    private lateinit var client: DiscoveryClient

    /** Discovery's results for the play screen. */
    private lateinit var clientEvents: EventBus<ClientEvent>

    inner class LocalBinder : Binder() {
        val service: UDPListenerService get() = this@UDPListenerService
    }

    private val binder: IBinder = LocalBinder()

    override fun onCreate() {
        super.onCreate()
        val container = (application as SimpleCoilApp).container
        gameState = container.gameState
        clientEvents = container.clientEvents
        client = DiscoveryClient(this, localAddress = { PlayerRepository.getIPAddress() })
        Log.d(TAG, "UDP Service initialized")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent): IBinder = binder

    override fun onDestroy() {
        stopListen()
        super.onDestroy()
    }

    /** Looks for a server on the LAN. */
    fun joinServer() = startDiscovery()

    /** Joins the server at [serverIP] (a typed address, with or without a leading "/"). */
    fun joinServer(serverIP: String?) {
        if (serverIP == null || serverIP.trim().isEmpty()) {
            sendFailedJoin()
            return
        }
        val ip = serverIP.removePrefix("/")
        Thread {
            try {
                val address = InetAddress.getByName(ip)
                val key = address.hostAddress ?: ip
                // v08: connect only once the server has named its certificate in a beacon. A typed
                // address may not have sent one here yet, so ask it directly.
                if (!pins.has(key)) {
                    startDiscovery()
                    val until = SystemClock.elapsedRealtime() + PIN_WAIT_MS
                    while (!pins.has(key) && key !in olderServers && SystemClock.elapsedRealtime() < until) {
                        client.ask(address)
                        Thread.sleep(ASK_INTERVAL_MS)
                    }
                }
                when {
                    pins.has(key) -> {
                        gameState.serverIP = address
                        clientEvents.publish(ClientEvent.ServerReply(null))
                    }
                    key in olderServers -> clientEvents.publish(ClientEvent.VersionError)
                    else -> {
                        Log.w(TAG, "No SimpleCoil server answered at $ip")
                        sendFailedJoin()
                    }
                }
            } catch (e: UnknownHostException) {
                Log.e(TAG, "Failed to resolve server IP: $ip", e)
                sendFailedJoin()
            } catch (e: InterruptedException) {
                sendFailedJoin()
            }
        }.start()
    }

    /** Each server's certificate fingerprint, from its beacon (v08). */
    private val pins get() = (application as SimpleCoilApp).container.serverPins

    /** Servers whose beacon named no certificate: an older SimpleCoil, which this one can't join. */
    private val olderServers: MutableSet<String> = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap())

    fun joinServer(serverIP: InetAddress?) {
        if (serverIP != null) joinServer(serverIP.hostAddress) else sendFailedJoin()
    }

    private val _discoveredServers = kotlinx.coroutines.flow.MutableStateFlow<List<com.simplecoil.simplecoil.network.discovery.DiscoveredServer>>(emptyList())
    val discoveredServers: kotlinx.coroutines.flow.StateFlow<List<com.simplecoil.simplecoil.network.discovery.DiscoveredServer>> get() = _discoveredServers
    private val serverMap = java.util.concurrent.ConcurrentHashMap<String, com.simplecoil.simplecoil.network.discovery.DiscoveredServer>()

    fun startDiscovery() {
        client.start(object : DiscoveryClient.Listener {
            override fun onServerDiscovered(
                serverIp: String,
                serverName: String,
                pingMs: Long,
                inGame: Boolean,
                allowJoinInProgress: Boolean,
                fingerprint: String?,
            ) {
                val now = android.os.SystemClock.elapsedRealtime()
                if (fingerprint != null) {
                    pins.put(serverIp, fingerprint)
                    olderServers -= serverIp
                } else {
                    olderServers += serverIp
                }
                val old = serverMap[serverIp]
                val smoothedPing = if (old != null && old.pingMs > 0) {
                    (old.pingMs * 0.7 + pingMs * 0.3).toLong()
                } else {
                    pingMs
                }
                serverMap[serverIp] = com.simplecoil.simplecoil.network.discovery.DiscoveredServer(
                    serverIp, serverName, smoothedPing, now, inGame, allowJoinInProgress
                )
                val active = serverMap.values.filter { now - it.lastSeenMs < 2_500L }
                _discoveredServers.value = active
            }

            override fun onDiscoveryFailed(errorReason: String?) {
                Log.e(TAG, "Discovery failed: $errorReason")
                sendFailedJoin()
            }
        })
    }

    /** Stops looking for a server. */
    fun stopListen() {
        client.stop()
        serverMap.clear()
        _discoveredServers.value = emptyList()
    }

    fun endScanning() = stopListen()

    private fun sendFailedJoin() {
        clientEvents.publish(ClientEvent.FailedToJoin)
    }

    private companion object {
        const val TAG = "UDPSvc"
        /** How long a typed address has to send its beacon. */
        const val PIN_WAIT_MS = 4_000L
        const val ASK_INTERVAL_MS = 500L
    }
}
