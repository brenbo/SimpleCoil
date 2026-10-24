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

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.simplecoil.simplecoil.data.repository.GameSettingsRepository
import com.simplecoil.simplecoil.data.repository.GameStateRepository
import com.simplecoil.simplecoil.data.repository.PlayerRepository
import com.simplecoil.simplecoil.domain.loadout.Fix
import com.simplecoil.simplecoil.domain.map.LatLon
import com.simplecoil.simplecoil.domain.map.TeamSpawnsCache
import com.simplecoil.simplecoil.network.ServerEvent
import com.simplecoil.simplecoil.network.ServerCommand
import com.simplecoil.simplecoil.network.ServerMessage
import com.simplecoil.simplecoil.network.voice.VoicePeer
import com.simplecoil.simplecoil.network.voice.VoiceRelay
import com.simplecoil.simplecoil.network.WireCodec
import com.simplecoil.simplecoil.network.discovery.DiscoveryBeacon
import com.simplecoil.simplecoil.network.server.GameServer
import com.simplecoil.simplecoil.network.server.LiveGame
import com.simplecoil.simplecoil.network.server.ServerGps
import com.simplecoil.simplecoil.network.server.SavedServerSettings
import com.simplecoil.simplecoil.network.server.SessionPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The dedicated server's foreground service (dedicated server design §5.1, S3): hosts the
 * [GameServer], the discovery beacon (the only one, S5) and the Wi-Fi lock, and shows the
 * notification. [ServerController] starts, binds and stops it; nothing else talks to it.
 */
class TcpServer : Service() {

    lateinit var server: GameServer
        private set

    private lateinit var container: AppContainer
    private lateinit var beacon: DiscoveryBeacon
    private val scope: CoroutineScope = MainScope()
    private var notificationUpdates: Job? = null
    private var serving = false
    private var screenVisible = false
    private var wifiLock: WifiManager.WifiLock? = null
    private lateinit var serverPlayers: PlayerRepository
    private lateinit var serverSettings: GameSettingsRepository
    private var liveGame: LiveGame? = null
    private var spawnMoves: Job? = null
    private var gpsReports: Job? = null

    /** Voice chat's relay: each phone's talk to whoever may hear it. */
    private var voiceRelay: VoiceRelay? = null

    inner class LocalBinder : Binder() {
        val service: TcpServer get() = this@TcpServer
    }

    private val binder: IBinder = LocalBinder()

    override fun onBind(intent: Intent): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        container = (application as SimpleCoilApp).container
        // The server's own state, for as long as it runs (app container Q3): running a server never
        // touches the play screen's player id, name, settings or game state.
        val settings = GameSettingsRepository()
        val gameState = GameStateRepository()
        serverSettings = settings
        serverPlayers = PlayerRepository(settings, gameState)
        server = GameServer(settings, serverPlayers, gameState, container.serverEvents, container.clock,
            saveTeamMode = { container.userPreferences.saveTeamMode(it) },
            liveGameProvider = { liveGame },
            serverSockets = container.serverIdentity.serverSocketFactory)
        beacon = DiscoveryBeacon(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            container.serverController.stop()
            stopSelf() // in case nothing was bound: a notification left from an earlier run
        }
        // A server can't be restored after its process died (§5.4)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        if (serving) stopServer()
        scope.cancel()
        server.shutdown()
    }

    /** Starts serving with the operator's [saved] settings: the socket, the beacon and the foreground. */
    fun startServer(saved: SavedServerSettings) {
        if (serving) return
        serving = true
        server.loadSettings(saved)
        server.start()
        val serverName = container.userPreferences.activePresetName()?.ifBlank { null } ?: SERVER_NAME
        // v08: phones accept only the certificate this fingerprint names
        beacon.fingerprint = container.serverIdentity.fingerprint
        beacon.start(serverName)
        scope.launch {
            server.snapshot.collect { snapshot ->
                val inGame = (snapshot.phase == com.simplecoil.simplecoil.network.server.SessionPhase.RUNNING)
                val allowJoin = serverSettings.isAllowJoinInProgress
                val name = container.userPreferences.activePresetName()?.ifBlank { null } ?: SERVER_NAME
                beacon.updateBeaconState(name, inGame, allowJoin)
            }
        }
        // The field the game and the phones get: the spawns for the team mode being played
        val teamSpawns = TeamSpawnsCache()
        container.fieldImageryServer.prepareConfig = { teamSpawns.forMode(it, serverSettings.teamLayout.mode) }
        container.fieldImageryServer.start()
        startLiveGame()
        // An admin may talk to everyone even with voice chat off
        container.fieldImageryServer.voice.isAdmin = { id -> serverPlayers.playerSettings[id.toByte()]?.isAdmin == true }
        // v08: a player's voice key goes only to their own phone; a new server run, new keys
        container.fieldImageryServer.voice.addressOf = { id -> serverPlayers.teamIPMap[id.toByte()] }
        container.fieldImageryServer.voice.forgetKeys()
        voiceRelay = VoiceRelay(container.fieldImageryServer.voice, ::voicePeers, { serverSettings.teamLayout.mode.isFreeForAll }).also { it.start() }
        server.setServerIp(PlayerRepository.getIPAddress()?.hostAddress ?: PlayerRepository.getIPAddressStr())
        startAsForeground()
        updateWifiLock()
    }

    fun updateServerName(name: String) {
        val inGame = (server.snapshot.value.phase == com.simplecoil.simplecoil.network.server.SessionPhase.RUNNING)
        val allowJoin = serverSettings.isAllowJoinInProgress
        beacon.updateBeaconState(name, inGame, allowJoin)
    }

    /** Kicks every player, then stops the beacon and the server and leaves the foreground. */
    fun stopServer() {
        if (!serving) return
        serving = false
        server.sendToAll(WireCodec.encode(ServerMessage.Command(ServerCommand.ServerCancel)))
        beacon.stop()
        spawnMoves?.cancel()
        spawnMoves = null
        gpsReports?.cancel()
        gpsReports = null
        container.serverGps.value = emptyList()
        container.fieldImageryServer.liveConfig = null
        container.fieldImageryServer.extras = null
        container.liveGame = null
        voiceRelay?.stop()
        voiceRelay = null
        container.fieldImageryServer.sounds.clear()
        container.fieldImageryServer.stop()
        server.stop() // closes each connection once the SERVERCANCEL is written
        notificationUpdates?.cancel()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        updateWifiLock()
    }

    /**
     * The server's half of the field during games (map design: phases 3 and 4): moving spawns,
     * drops, scavenger ammo and shot distance, all served to the phones by the field service.
     */
    private fun startLiveGame() {
        val game = LiveGame(
            imagery = container.fieldImageryServer,
            savedField = { withContext(Dispatchers.IO) { container.serverFieldConfig.refresh()?.let(container.fieldImageryServer.prepareConfig) } },
            positions = ::playerFixes,
            teamOf = ::liveTeamOf,
            nameOf = { id -> server.snapshot.value.players.firstOrNull { it.playerId.toInt() == id }?.name },
            players = { server.snapshot.value.players.filter { it.connected }.associate { it.playerId.toInt() to liveTeamOf(it.playerId.toInt()) } },
            roster = { server.snapshot.value.players.map { it.playerId.toInt() }.toSet() },
            freeForAll = { serverSettings.teamLayout.mode.isFreeForAll },
            startDelayMillis = { serverSettings.startTime * 1000L },
            gpsOn = { serverSettings.isUseGPS },
            // Domination or Hardpoint won: the game ends as when the host presses End
            onWinner = { server.endGame() },
        )
        liveGame = game
        container.liveGame = game
        val eliminations = container.serverEvents.events
            .filterIsInstance<ServerEvent.Eliminated>()
            .map { it.victimId.toInt() to it.shooterId.toInt() }
        spawnMoves = LiveGame.launchIn(scope, game, server.snapshot.map { it.phase }, eliminations)
        gpsReports = scope.launch {
            while (true) {
                val players = server.snapshot.value.players.filter { it.connected }.map { it.playerId.toInt() to (it.name ?: "") }
                container.serverGps.value = ServerGps.report(players, playerFixes(), System.nanoTime() / 1_000_000L)
                delay(ServerGps.REFRESH_MILLIS)
            }
        }
    }

    /** A player's side in the field's games: their team, or in free for all, themselves. */
    private fun liveTeamOf(id: Int): Int = serverSettings.teamLayout.let { if (it.mode.isFreeForAll) id else it.teamOf(id) }

    /** Everyone voice might go to: team, address, connected, and admin (who may talk to all). */
    private fun voicePeers(): List<VoicePeer> = server.snapshot.value.players.map { row ->
        val id = row.playerId.toInt()
        VoicePeer(id, liveTeamOf(id), serverPlayers.teamIPMap[row.playerId], row.connected,
            serverPlayers.playerSettings[row.playerId]?.isAdmin == true)
    }

    /** Each player's last reported position and when it came, from the server's GPS reports. */
    private fun playerFixes(): Map<Int, Fix> = synchronized(serverPlayers) {
        serverPlayers.gpsData.entries
            .filter { (_, gps) -> gps.latitude != 0.0 || gps.longitude != 0.0 }
            .associate { (id, gps) -> id.toInt() to Fix(LatLon(gps.latitude, gps.longitude), gps.updatedAt) }
    }

    /** Wi-Fi stays awake while the server screen is in the foreground, and sleeps with the device otherwise (§5.6, Q3). */
    fun setScreenVisible(visible: Boolean) {
        screenVisible = visible
        updateWifiLock()
    }

    private fun updateWifiLock() {
        if (serving && screenVisible) {
            if (wifiLock?.isHeld == true) return
            val wifi = applicationContext.getSystemService(WifiManager::class.java) ?: return
            wifiLock = wifi.createWifiLock(wifiLockMode(), TAG).apply {
                setReferenceCounted(false)
                acquire()
            }
        } else {
            wifiLock?.let { if (it.isHeld) it.release() }
            wifiLock = null
        }
    }

    @Suppress("DEPRECATION") // HIGH_PERF is what API 23-28 have; LOW_LATENCY replaces it from 29
    private fun wifiLockMode(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(CHANNEL_ID, getString(R.string.server_notification_channel), NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(serviceChannel)
        }
    }

    /**
     * Keeps the process in the foreground while the server runs, so a minimized or backgrounded
     * server isn't frozen or killed (S3). The notification follows the player count and phase.
     */
    @SuppressLint("InlinedApi") // ServiceCompat ignores the type below Android 10
    private fun startAsForeground() {
        createNotificationChannel()
        val snapshot = server.snapshot.value
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(snapshot.playerCount, snapshot.phase),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } catch (e: Exception) {
            // Android 12+ refuses this while the app is in the background; serve on without it.
            Log.w(TAG, "Could not start foreground: " + e.message)
            return
        }
        notificationUpdates = scope.launch {
            server.snapshot.map { it.playerCount to it.phase }.distinctUntilChanged().collect { (count, phase) ->
                getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification(count, phase))
            }
        }
    }

    /** "N players · phase"; tapping it opens the server screen, and Stop server stops it (Q1). */
    private fun notification(playerCount: Int, phase: SessionPhase): Notification {
        val open = Intent(this, DedicatedServerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val stop = Intent(this, TcpServer::class.java).setAction(ACTION_STOP)
        val phaseText = getString(when (phase) {
            SessionPhase.LOBBY -> R.string.server_notification_lobby
            SessionPhase.RUNNING -> R.string.server_notification_running
            SessionPhase.POST_GAME -> R.string.server_notification_post_game
        })
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.server_notification_title))
            .setContentText(resources.getQuantityString(R.plurals.server_notification_players, playerCount, playerCount, phaseText))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE))
            .addAction(0, getString(R.string.server_notification_stop),
                PendingIntent.getService(this, 1, stop, PendingIntent.FLAG_IMMUTABLE))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    companion object {
        private const val TAG = "TCPServer"
        private const val NOTIFICATION_ID = 1339
        private const val CHANNEL_ID = "TcpServerServiceChannel"
        private const val SERVER_NAME = "SimpleCoil Dedicated Server"

        /** The notification's Stop server action. */
        const val ACTION_STOP = "com.simplecoil.simplecoil.action.STOP_SERVER"
    }
}
