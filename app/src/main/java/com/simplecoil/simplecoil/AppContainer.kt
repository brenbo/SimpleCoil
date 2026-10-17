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

package com.simplecoil.simplecoil

import android.app.Application
import android.os.SystemClock
import com.simplecoil.simplecoil.data.preferences.UserPreferencesRepository
import com.simplecoil.simplecoil.data.repository.GameSettingsRepository
import com.simplecoil.simplecoil.data.repository.GameStateRepository
import com.simplecoil.simplecoil.data.repository.PlayerRepository
import com.simplecoil.simplecoil.domain.loadout.LoadoutController
import com.simplecoil.simplecoil.domain.map.BorderController
import com.simplecoil.simplecoil.domain.map.SpawnController
import com.simplecoil.simplecoil.domain.session.BlasterSessionEngine
import com.simplecoil.simplecoil.domain.session.GameConfig
import com.simplecoil.simplecoil.domain.session.RepositoryGameConfig
import com.simplecoil.simplecoil.domain.session.RespawnHold
import com.simplecoil.simplecoil.domain.session.SessionSound
import com.simplecoil.simplecoil.hardware.audio.AudioHapticsManager
import com.simplecoil.simplecoil.hardware.audio.AudioHapticsPlayer
import com.simplecoil.simplecoil.hardware.audio.HapticsPlayer
import com.simplecoil.simplecoil.hardware.audio.SessionFeedback
import com.simplecoil.simplecoil.hardware.audio.SoundEffectPlayer
import com.simplecoil.simplecoil.hardware.blaster.BlasterManager
import com.simplecoil.simplecoil.hardware.blaster.BleConnectionManager
import com.simplecoil.simplecoil.hardware.blaster.BleDeviceRepository
import com.simplecoil.simplecoil.hardware.location.MapLocationManager
import com.simplecoil.simplecoil.network.ClientEvent
import com.simplecoil.simplecoil.network.EventBus
import com.simplecoil.simplecoil.network.ServerEvent
import com.simplecoil.simplecoil.network.imagery.FieldConfigStore
import com.simplecoil.simplecoil.network.imagery.FieldImageryClient
import com.simplecoil.simplecoil.network.imagery.FieldImageryServer
import com.simplecoil.simplecoil.network.imagery.FieldPackStore
import com.simplecoil.simplecoil.network.imagery.FieldPresetStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import java.io.File

/**
 * The application's object graph (manual DI, APP_CONTAINER_DESIGN.md). Built once in
 * [SimpleCoilApp.onCreate]. Plain classes get their dependencies through their constructors from
 * here; Android entry points read it from the application.
 */
class AppContainer(private val app: Application) {

    val dispatchers = AppDispatchers()

    /** Monotonic milliseconds, counting deep sleep. */
    val clock: () -> Long = SystemClock::elapsedRealtime

    /** Main-thread scope that lives as long as the process. */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + dispatchers.mainImmediate)

    // State

    val settings = GameSettingsRepository()
    val gameState = GameStateRepository()
    val players = PlayerRepository(settings, gameState)
    val gameConfig: GameConfig = RepositoryGameConfig(settings, players, gameState)

    /** The player's saved settings ("SimpleCoil" preferences). */
    val userPreferences: UserPreferencesRepository by lazy { UserPreferencesRepository(app) }

    // Hardware and session

    val blasterManager: BlasterManager by lazy { BlasterManager(appScope) }

    val bleDeviceRepository: BleDeviceRepository by lazy { BleDeviceRepository(blasterManager) }

    val bleConnectionManager: BleConnectionManager by lazy {
        BleConnectionManager(app, blasterManager, appScope, saveDeviceAddress = { userPreferences.saveDeviceAddress(it) })
            .also { it.registerAdapterStateReceiver() }
    }

    /** The blaster session (D4: app-scoped). Its timers post to the main thread like Handlers. */
    val sessionEngine: BlasterSessionEngine by lazy {
        BlasterSessionEngine(
            blaster = bleDeviceRepository,
            config = gameConfig,
            preferences = userPreferences,
            scope = CoroutineScope(SupervisorJob() + dispatchers.main),
            clock = clock,
        ).also {
            it.network = networkGateway
            it.attach(bleDeviceRepository.events)
            SessionFeedback(soundEffects, haptics).attach(it.effects, appScope)
        }
    }

    private val audioHaptics: AudioHapticsPlayer by lazy {
        AudioHapticsPlayer(AudioHapticsManager().also { it.initialize(app) })
    }

    /** Game sounds (preloaded SoundPool). */
    val soundEffects: SoundEffectPlayer get() = audioHaptics

    val haptics: HapticsPlayer get() = audioHaptics

    /** The map's location tracking and markers; process-wide, as the map fragment comes and goes. */
    val mapLocation: MapLocationManager by lazy { MapLocationManager(players, settings) }

    // Field imagery (map design: Field imagery packs)

    /** The field a server last sent this phone, for the play screen's map. */
    val playerFieldStore: FieldPackStore by lazy { FieldPackStore(File(app.filesDir, "field/player")) }

    /** The field the dedicated server downloaded and shares with the phones. */
    val serverFieldStore: FieldPackStore by lazy { FieldPackStore(File(app.filesDir, "field/server")) }

    /** The border and rules a server last sent this phone (map design: Map objects). */
    val playerFieldConfig: FieldConfigStore by lazy { FieldConfigStore(File(app.filesDir, "field/player")) }

    /** The border and rules the dedicated server's host set up. */
    val serverFieldConfig: FieldConfigStore by lazy { FieldConfigStore(File(app.filesDir, "field/server")) }

    /** The dedicated server's saved fields. */
    val fieldPresets: FieldPresetStore by lazy { FieldPresetStore(File(app.filesDir, "field/presets")) }

    /** Serves the server's field while the dedicated server runs. */
    val fieldImageryServer: FieldImageryServer by lazy { FieldImageryServer(serverFieldStore, serverFieldConfig) }

    /** Fetches the server's field into [playerFieldStore] and [playerFieldConfig]. */
    val fieldImageryClient: FieldImageryClient by lazy {
        FieldImageryClient(playerFieldStore, appScope, dispatchers.io, clock, configs = playerFieldConfig)
    }

    /** Walk-back respawn and moving spawns on this phone (map design: Respawn points). */
    val spawnController: SpawnController by lazy {
        val engine = sessionEngine
        SpawnController(
            field = playerFieldConfig.config,
            session = engine.state,
            actions = object : SpawnController.Actions {
                override fun holdRespawn(held: Boolean) = engine.holdRespawn(RespawnHold.AWAY_FROM_SPAWN, held)
                override fun endShield() = engine.endRespawnShield()
            },
            myTeam = { settings.teamLayout.let { it.teamOf(players.playerID) to it.mode.isFreeForAll } },
            inGame = { gameState.gameState != GameStateRepository.GAME_STATE_NONE },
            scope = appScope,
            clock = clock,
        )
    }

    /** Ammo, drops, power-ups, killstreaks and the longest shot on this phone (map design: phase 4). */
    val loadoutController: LoadoutController by lazy {
        val engine = sessionEngine
        val client = fieldImageryClient
        LoadoutController(
            field = playerFieldConfig.config,
            session = engine.state,
            blaster = object : LoadoutController.Blaster {
                override fun setAmmoReserve(magazines: Int?) = engine.setAmmoReserve(magazines)
                override fun grantShotMode(shotMode: Int) = engine.grantShotMode(shotMode)
                override fun healFull() = engine.healFull()
                override fun addOvershield(hits: Int) = engine.addOvershield(hits)
                override fun addExtraLife() = engine.addExtraLife()
            },
            server = object : LoadoutController.Server {
                override fun now(): Long = client.serverNow()
                override fun claim(dropId: String, playerId: Int) = gameState.serverIP?.let { client.claim(it, dropId, playerId) }
                override fun uav(playerId: Int) = gameState.serverIP?.let { client.uav(it, playerId) }
                override fun longestShot() = gameState.serverIP?.let { client.longestShot(it) }
                override fun refresh() = client.syncSoon(gameState.serverIP, force = true)
            },
            me = {
                val id = players.playerID.toInt()
                val layout = settings.teamLayout
                id to if (layout.mode.isFreeForAll) id else layout.teamOf(id)
            },
            inGame = { gameState.gameState != GameStateRepository.GAME_STATE_NONE },
            inOwnSpawn = { spawnController.isInOwnSpawn() },
            feedback = object : LoadoutController.Feedback {
                override fun play(sound: SessionSound) = soundEffects.play(sound)
                override fun vibrate() = haptics.vibrate()
            },
            kills = clientEvents.events.filterIsInstance<ClientEvent.Eliminated>().map { },
            scope = appScope,
            io = dispatchers.io,
            clock = clock,
        )
    }

    /** Warns and penalises a player who leaves the field during a game (map design: Border). */
    val borderController: BorderController by lazy {
        val engine = sessionEngine
        BorderController(
            field = playerFieldConfig.config,
            penalties = object : BorderController.Penalties {
                override fun setOutOfBounds(disabled: Boolean) = engine.setOutOfBounds(disabled)
                override fun holdRespawn(held: Boolean) = engine.holdRespawn(RespawnHold.OUT_OF_BOUNDS, held)
                override fun damage(amount: Int) = engine.applyOutOfBoundsDamage(amount)
                override fun eliminate() = engine.eliminateOutOfBounds()
            },
            inGame = { gameState.gameState != GameStateRepository.GAME_STATE_NONE },
            feedback = object : BorderController.Feedback {
                override fun play(sound: SessionSound) = soundEffects.play(sound)
                override fun vibrate() = haptics.vibrate()
            },
            scope = appScope,
            clock = clock,
        )
    }

    // Network

    /** What the network client and discovery report to the play screen and the map (network design §6). */
    val clientEvents = EventBus<ClientEvent>(name = "ClientEvents")

    /** What the dedicated server reports to its screen. */
    val serverEvents = EventBus<ServerEvent>(name = "ServerEvents")

    /** Starts, stops and commands the dedicated server (dedicated server design §5.1). */
    val serverController: ServerController by lazy { TcpServerController(app, appScope) { userPreferences } }

    /** Network services, client events and the network session state (R13/R14). */
    val networkGateway: NetworkSessionGateway by lazy {
        NetworkSessionGateway(app, appScope, settings, players, bus = clientEvents)
    }

    /** The notifications' Quit: leaves the server, drops the blaster, stops everything and exits. */
    val appQuitter: AppQuitter by lazy {
        AppQuitter(app, appScope, dispatchers.io, networkGateway, bleConnectionManager, serverController)
    }
}
