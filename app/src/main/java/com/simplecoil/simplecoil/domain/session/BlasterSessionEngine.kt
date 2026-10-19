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

package com.simplecoil.simplecoil.domain.session

import android.util.Log
import com.simplecoil.simplecoil.data.repository.GameSettingsRepository
import com.simplecoil.simplecoil.data.repository.GameStateRepository
import com.simplecoil.simplecoil.data.repository.PlayerRepository
import com.simplecoil.simplecoil.domain.team.TeamLayout
import com.simplecoil.simplecoil.hardware.blaster.BlasterEvent
import com.simplecoil.simplecoil.hardware.blaster.GattProtocolHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The blaster session: R5-R12 moved out of FullscreenActivity (REFACTOR_DESIGN.md §2.2.1).
 * Button edges and the first-packet setup, the two-stage reload, hits/elimination/lives, spawn,
 * respawn shield, game countdown/chronometer, battery, shot mode and recoil.
 *
 * App-scoped (D4): it consumes the blaster's events itself, so a session keeps running while the
 * UI is in the background. Main thread only. [scope] runs the protocol and game delays and must
 * post like a Handler (Dispatchers.Main in the app, a test dispatcher in tests); [clock] is
 * SystemClock.elapsedRealtime() in the app; [wallClock] times the shot-fired rate limit.
 */
class BlasterSessionEngine(
    private val blaster: BlasterCommands,
    private val config: GameConfig,
    private val preferences: SessionPreferences,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val wallClock: () -> Long = System::currentTimeMillis,
) {
    /** Network session; set by the UI while one exists. Null behaves like "no network". */
    var network: SessionNetwork? = null

    private val _state = MutableStateFlow(SessionState(
        health = config.fullHealth,
        maxHealth = config.fullHealth,
        shotMode = GameSettingsRepository.SHOT_MODE_SINGLE,
        grenadePaired = config.pairedGrenadeId.toInt() != 0,
    ))
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<SessionEffect>(extraBufferCapacity = EFFECT_BUFFER)
    val effects: SharedFlow<SessionEffect> = _effects.asSharedFlow()

    // Connection
    /** Telemetry has arrived since the last GATT connect. */
    var isCommunicating = false
        private set
    /** A blaster has sent telemetry since the last "disconnect & quit". */
    var hasConnectedToBlaster = false

    // Button counters, re-baselined to the first packet of each connection.
    private var lastTriggerCount: Byte = 0
    private var lastReloadButtonCount: Byte = 0
    private var lastThumbButtonCount: Byte = 0
    private var lastPowerButtonCount: Byte = 0
    private var lastTeam: Byte = 0
    private var lastShotCount: Byte = 0
    private var emptyTriggerCount = 0
    private var lastShotFired = 0L

    // Thumb button hold, from the buttons-held byte. A press only cycles the shot mode once it is
    // released short of a long press, so holding for the scoreboard leaves the shot mode alone.
    private var thumbHeldSince = NOT_HELD
    private var thumbLongPressed = false
    private var thumbPressPending = false
    /** A long press just ended: a thumb count in the next packet belongs to it, not a new press. */
    private var thumbLongPressReleased = false

    /** Reload held while awaiting a manual respawn, from the buttons-held byte. */
    private var reloadHoldTimer: Job? = null

    // Reload
    private var reloading = RELOADING_ELIMINATED

    // Player
    private var health = config.fullHealth
    private var hitsTaken = 0
    private var eliminationCount = 0
    private var hasLivesLimit = false
    private var lives = 0
    private var isShielded = false
    private var currentShotMode = GameSettingsRepository.SHOT_MODE_SINGLE
    private var recoilEnabled = true
    private var blasterType: Byte = GattProtocolHandler.BLASTER_TYPE_PISTOL

    /** Team of the player, as computed by the team display. */
    var networkTeam = 0

    // Game clock
    private var startGameTimer = true
    private var gameTimerRunning = false
    private var startingGameLabel = false

    // Timers
    private var spawnTimer: Job? = null
    private var reloadTimer: Job? = null
    private var shieldTimer: Job? = null
    private var gameCountdown: Job? = null
    private var hitOverlayTimer: Job? = null

    private val hitResolver = HitResolver(wallClock)
    private val battery = BatteryLevelPolicy.Averager()
    /** Lookups for one packet, with every team answer taken from the same [layout]. */
    private fun hitLookups(layout: TeamLayout) = object : HitResolver.Lookups {
        override fun networkTeam(playerId: Byte) = layout.teamOf(playerId)
        override fun grenadePairing(grenadeId: Int) = config.grenadePairing(grenadeId)
        override fun damage(playerId: Byte) = config.playerDamage(playerId)
    }

    /** Starts consuming the blaster's events. */
    fun attach(events: Flow<BlasterEvent>) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            events.collect { onBlasterEvent(it) }
        }
    }

    fun onBlasterEvent(event: BlasterEvent) {
        when (event) {
            is BlasterEvent.Connected -> isCommunicating = false // switch layouts on the first packet
            BlasterEvent.ServicesDiscovered -> {
                if (config.gameState == GameStateRepository.GAME_STATE_NONE) {
                    emit(SessionEffect.Sound(SessionSound.CONNECTED))
                } else if (config.gameState == GameStateRepository.GAME_STATE_RUNNING) {
                    startReload()
                }
            }
            is BlasterEvent.TypeDiscovered -> {
                blasterType = event.blasterType
                _state.update { it.copy(blasterType = event.blasterType) }
            }
            is BlasterEvent.Telemetry -> processTelemetry(event.data)
            BlasterEvent.WriteFinished -> onWriteFinished()
            else -> Unit
        }
    }

    // ---------------------------------------------------------------------------------------
    // Settings
    // ---------------------------------------------------------------------------------------

    /** Restores the saved shot mode and recoil choice without writing to the blaster. */
    fun restore(shotMode: Int, recoilEnabled: Boolean) {
        currentShotMode = shotMode
        this.recoilEnabled = recoilEnabled
        _state.update { it.copy(shotMode = shotMode, recoilEnabled = recoilEnabled) }
    }

    val shotMode: Int get() = currentShotMode

    // Config 00 00 09 xx yy ff c8 ff ff 80 01 34 - xx is the number of shots and if you set yy to 01 for full auto for xx shots or 00 for single shot mode, increasing yy decreases RoF
    // setting 03 03 for shots and RoF gives a good 3 shot burst, 03 01 is so fast that you feel 1 recoil for 3 shots
    fun setShotMode(shotMode: Int) {
        if (shotMode == GameSettingsRepository.SHOT_MODE_SINGLE || shotMode == GameSettingsRepository.SHOT_MODE_BURST || shotMode == GameSettingsRepository.SHOT_MODE_FULL_AUTO) {
            currentShotMode = shotMode
            _state.update { it.copy(shotMode = shotMode) }
        }
        preferences.saveShotMode(currentShotMode)
        blaster.setShotMode(shotMode, config.firingMode)
    }

    // Config 10 00 02 02 ff and 15 sets of 00 disables recoil
    // Config 10 00 02 03 ff and 15 sets of 00 enables recoil
    fun setRecoil(enabled: Boolean) {
        recoilEnabled = enabled
        _state.update { it.copy(recoilEnabled = enabled) }
        preferences.saveRecoilEnabled(enabled)
        blaster.setRecoil(enabled)
    }

    fun applyCurrentRecoilSetting() {
        setRecoil(RecoilPolicy.effective(config.serverRecoilSetting, recoilEnabled))
    }

    /** Player settings changed: reapply recoil and move off a shot mode that's no longer allowed. */
    fun applyPlayerSettings() {
        applyCurrentRecoilSetting()
        ShotModePolicy.coerceToAllowed(currentShotMode, allowSingle(), allowBurst(),
            allowAuto())?.let { setShotMode(it) }
        val allowedModes = listOf(allowSingle(), allowBurst(), allowAuto()).count { it }
        _state.update {
            it.copy(shotModeChangeable = allowedModes > 1,
                recoilChangeable = config.serverRecoilSetting == GameSettingsRepository.RECOIL_SETTING_DEFAULT)
        }
    }

    /** The power button or the recoil tile: toggle recoil, unless the server forces it. */
    fun toggleRecoil() {
        val recoil = RecoilPolicy.onPowerButton(config.serverRecoilSetting, recoilEnabled)
        setRecoil(recoil.enabled)
        if (recoil.forced) {
            emit(SessionEffect.Toast(if (recoil.enabled) SessionToast.RECOIL_FORCED_ENABLED else SessionToast.RECOIL_FORCED_DISABLED))
        }
    }

    /** The thumb button or the shot mode tile: move to the next allowed shot mode. */
    fun cycleShotMode() {
        ShotModePolicy.nextOnThumb(currentShotMode, allowSingle(), allowBurst(),
            allowAuto())?.let { setShotMode(it) }
    }

    /** The "blaster disconnected" dialog was dismissed by a reconnect: re-send recoil (§1.6: +600 ms). */
    fun onDisconnectDialogDismissed() {
        scope.launch {
            delay(DISCONNECT_DIALOG_RECOIL_DELAY_MILLIS)
            applyCurrentRecoilSetting()
        }
    }

    /**
     * Sends the player id to the blaster (when it is a valid id). Sending to Command a packet
     * with 10 00 80 00 PLAYER# sets the player ID. This works for initially setting the ID, but
     * does not work consistently afterwards; a reload procedure changes it reliably.
     */
    fun sendTeamToBlaster() {
        val playerId = config.playerId
        if (playerId >= 1 && playerId <= GameSettingsRepository.MAX_PLAYER_ID) {
            Log.d(TAG, "setting player ID to $playerId")
            blaster.setTeam(playerId)
        }
    }

    private fun setTeam() {
        sendTeamToBlaster()
        emit(SessionEffect.TeamSent)
    }

    /** Lives limit from the game limit (or a server override); updates the lives/eliminations display. */
    fun applyGameLimit() {
        if ((config.gameLimit and GameSettingsRepository.GAME_LIMIT_LIVES) != 0) {
            hasLivesLimit = true
            lives = config.livesLimit
            setEliminationDisplay(config.livesLimit)
        } else {
            hasLivesLimit = false
            lives = 0
            setEliminationDisplay(eliminationCount)
        }
        if (config.overrideLives) {
            if (config.overrideLivesValue != 0) {
                hasLivesLimit = true
                lives = config.overrideLivesValue
                setEliminationDisplay(config.overrideLivesValue)
            } else {
                hasLivesLimit = false
                lives = 0
                setEliminationDisplay(eliminationCount)
            }
        }
    }

    val hasLivesLimitSet: Boolean get() = hasLivesLimit

    // ---------------------------------------------------------------------------------------
    // Game lifecycle (the session part; screen and network parts stay with the caller)
    // ---------------------------------------------------------------------------------------

    fun startGame() {
        eliminationCount = if (hasLivesLimit) lives else 0
        setEliminationDisplay(eliminationCount)
        health = config.fullHealth
        _state.update { it.copy(gameInProgress = true, health = health, maxHealth = health) }
        startingGameLabel = true
        startGameTimer = true
        setShotMode(currentShotMode) // make sure that the shot mode is correctly set at the start of each game
        startSpawn("")
    }

    fun endGame() {
        config.gameState = GameStateRepository.GAME_STATE_NONE
        spawnTimer?.cancel()
        spawnTimer = null
        shieldTimer?.cancel()
        shieldTimer = null
        isShielded = false
        reloadTimer?.cancel()
        reloadTimer = null
        gameTimerRunning = false
        gameCountdown?.cancel()
        gameCountdown = null
        clearHitOverlay()
        val countdownText = if ((config.gameLimit and GameSettingsRepository.GAME_LIMIT_TIME) != 0) {
            ClockFormat.minutes(config.timeLimitMinutes.toLong())
        } else {
            (_state.value.clock as? GameClock.Stopped)?.countdownText
        }
        _state.update { it.copy(gameInProgress = false, spawn = SpawnPhase.None, shieldSeconds = null, clock = GameClock.Stopped(countdownText)) }
        outOfBounds = false
        respawnHolds.clear()
        extraLives = 0
        loseDeathPowerUps()
        _state.update { it.copy(extraLives = 0) }
        reloadHoldTimer?.cancel()
        reloadHoldTimer = null
        if (reloading == RELOADING_OUT_OF_BOUNDS) reloading = RELOADING_ELIMINATED
        startReload(RELOADING_ELIMINATED)
    }

    /** Server game update: eliminations and remaining game time from the host. */
    fun syncFromServer(eliminations: Int, timeRemaining: Long) {
        eliminationCount = if (hasLivesLimit) lives - eliminations else eliminations
        setEliminationDisplay(eliminationCount)
        if (timeRemaining > 0 && config.gameState == GameStateRepository.GAME_STATE_RUNNING) {
            gameCountdown?.cancel()
            startGameCountdown(timeRemaining)
            gameTimerRunning = true
        } else {
            gameCountdown?.cancel()
            gameCountdown = null
        }
    }

    /** Someone else fired: keeps us from spamming shots-fired messages. */
    fun onRemoteShotFired() {
        lastShotFired = wallClock() + HIT_ANIMATION_DURATION_MILLISECONDS
    }

    /** Full disconnect: forget the shot count and restart the battery average. */
    fun onBlasterReset() {
        lastShotCount = 0
        battery.reset()
    }

    /** Unpairs our grenade; a pairing lasts from game to game, but not past the connect screen or the app. */
    fun forgetGrenade() {
        config.pairedGrenadeId = 0
        _state.update { it.copy(grenadePaired = false) }
    }

    val eliminations: Int get() = eliminationCount

    // ---------------------------------------------------------------------------------------
    // Reload
    // ---------------------------------------------------------------------------------------

    /* Reloading is done in 2 stages. The first stage effectively tells the tagger that it can't
       shoot anymore. We also use this state when the player is eliminated so they can't shoot while
       out of the game. At the second stage, we tell the tagger how many shots it now has. With a
       normal reload sequence, we wait until the tagger has received the first stage command, and
       we automatically start a timer to finish the reload after the reload wait timer is up.
       We don't want this timer to run/finish if the player is eliminated, hence the eliminated
       state here. */
    private fun startReload(reloadStatus: Int = RELOADING_STARTED) {
        if (reloading != RELOADING_NONE) return
        if (reloadStatus == RELOADING_STARTED && reserve == 0) {
            // Limited ammo and none left: no reload until a refill
            emit(SessionEffect.Sound(SessionSound.EMPTY))
            emit(SessionEffect.Toast(SessionToast.OUT_OF_AMMO))
            return
        }
        reloading = reloadStatus
        _state.update { it.copy(reloadBarShown = true) }
        blaster.startReload(config.playerId)
    }

    private fun finishReload() {
        if ((reloading != RELOADING_STARTED && reloading != RELOADING_ELIMINATED && reloading != RELOADING_OUT_OF_BOUNDS) ||
            config.gameState != GameStateRepository.GAME_STATE_RUNNING) return
        if (outOfBounds) {
            // Out of bounds: the blaster stays held until the player is back on the field
            reloading = RELOADING_OUT_OF_BOUNDS
            return
        }
        // A reload the player asked for uses a magazine from a limited reserve; a respawn doesn't
        if (reloading == RELOADING_STARTED) reserve?.let {
            setAmmoReserve((it - 1).coerceAtLeast(0))
            // That was the last spare: this magazine is all there is
            if (it == 1) emit(SessionEffect.Sound(SessionSound.LAST_MAG))
        }
        reloading = RELOADING_FINISHING
        emptyTriggerCount = 0
        Log.d(TAG, "Finishing reload")
        setShotsRemaining(config.fullReload)
        blaster.finishReload(config.playerId, config.fullReload)
    }

    private fun onWriteFinished() {
        if (reloading == RELOADING_STARTED) {
            // A timer so it can be cancelled if the player is eliminated while waiting to reload
            val reloadTime = config.reloadTimeMillis
            reloadTimer = scope.countDown(clock, reloadTime, reloadTime, onTick = {}, onFinish = { finishReload() })
        } else if (reloading == RELOADING_FINISHING) {
            reloading = RELOADING_NONE
            _state.update { it.copy(reloadBarShown = false) }
            // A quick automatic reload is barely noticeable, so it's not worth a sound
            if (!config.reloadOnEmpty || config.reloadTimeMillis > RELOAD_ON_EMPTY_SILENT_MAX_MILLIS) {
                emit(SessionEffect.Sound(SessionSound.RELOAD))
            }
            Log.d(TAG, "Reload finished")
            // Went out of bounds while the reload was being written
            if (outOfBounds) startReload(RELOADING_OUT_OF_BOUNDS)
        }
    }

    // ---------------------------------------------------------------------------------------
    // Out of bounds (map design: Border)
    // ---------------------------------------------------------------------------------------

    /** The player is off the field: the blaster can't shoot (held as if reloading) while [disabled]. */
    var outOfBounds = false
        private set

    private val respawnHolds = HashSet<RespawnHold>()

    /** Something keeps the player from respawning: off the field, or not at a spawn yet. */
    val respawnHeld: Boolean get() = respawnHolds.isNotEmpty()

    /**
     * Holds (or lets go of) the respawn for [reason]. A respawn countdown that ends while held
     * waits ([SpawnPhase.AwaitingReturn]) until nothing holds it, then the player spawns.
     */
    /** Eliminations by a hit so far: a change tells a packet's hit eliminated the player. */
    private var hitEliminations = 0

    /** The grenade that last eliminated this player (null: none; 0 is a grenade id too), and until when it can't again. */
    private var immuneGrenade: Int? = null
    private var immuneUntil = 0L

    /** The grenade a hit slot carries, if it is a grenade's damage; null otherwise. */
    private fun damagingGrenade(hitBy: Int, shot: Byte): Int? =
        if (hitBy == PlayerRepository.GRENADE_PLAYER_ID && !GrenadeDecoder.isGrenadeControlEvent(hitBy, shot)) GrenadeDecoder.grenadeId(shot) else null

    /** Battle Royale once respawns are over: an eliminated player is out for the rest of the game. */
    private var noRespawn = false

    fun setNoRespawn(on: Boolean) {
        noRespawn = on
    }

    /** Hits do nothing: a Search & Destroy round hasn't started yet, or is over. */
    private var hitsPaused = false

    fun setHitsPaused(on: Boolean) {
        hitsPaused = on
    }

    /** A new round (Search & Destroy): a player out of this round comes back with the respawn countdown. */
    fun revive() {
        val out = _state.value.spawn as? SpawnPhase.OutOfLives ?: return
        if (config.gameState != GameStateRepository.GAME_STATE_ELIMINATED) return
        startSpawnCountdown(out.eliminatedBy)
    }

    fun holdRespawn(reason: RespawnHold, held: Boolean) {
        val changed = if (held) respawnHolds.add(reason) else respawnHolds.remove(reason)
        if (!changed) return
        // Manual respawn: tell the player whether they need to get to a spawn first
        (_state.value.spawn as? SpawnPhase.AwaitingRespawn)?.let { awaiting ->
            _state.update { it.copy(spawn = awaiting.copy(needsSpawn = respawnHolds.isNotEmpty())) }
            return
        }
        val waiting = _state.value.spawn as? SpawnPhase.AwaitingReturn ?: return
        if (respawnHolds.isEmpty()) {
            spawned()
        } else {
            _state.update { it.copy(spawn = waiting.copy(outOfBounds = RespawnHold.OUT_OF_BOUNDS in respawnHolds)) }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Loadout (map design: Ammo, drops and power-ups)
    // ---------------------------------------------------------------------------------------

    /** Magazines left with limited ammo; null for unlimited. */
    private var reserve: Int? = null

    /** Shot modes a power-up allows on top of the server's, until the player is eliminated. */
    private var grantedShotModes = emptySet<Int>()
    private var overshield = 0
    private var extraLives = 0

    /** Limited ammo: [magazines] left (null: unlimited, as before). */
    fun setAmmoReserve(magazines: Int?) {
        reserve = magazines
        _state.update { it.copy(reserve = magazines) }
    }

    /** Full auto or burst from a drop or killstreak, until the player is eliminated; switches to it. */
    fun grantShotMode(shotMode: Int) {
        grantedShotModes = grantedShotModes + shotMode
        _state.update { it.copy(poweredShotMode = shotMode) }
        updateShotModeChangeable()
        setShotMode(shotMode)
    }

    fun healFull() {
        if (config.gameState != GameStateRepository.GAME_STATE_RUNNING) return
        health = config.fullHealth
        _state.update { it.copy(health = health) }
    }

    /** The next [hits] hits do nothing (lost when eliminated). */
    fun addOvershield(hits: Int) {
        overshield += hits
        _state.update { it.copy(overshieldHits = overshield) }
    }

    /** The next elimination respawns in place. */
    fun addExtraLife() {
        extraLives++
        _state.update { it.copy(extraLives = extraLives) }
    }

    /** Eliminated: full auto, burst and overshield go; the shot mode falls back to an allowed one. */
    private fun loseDeathPowerUps() {
        overshield = 0
        if (grantedShotModes.isNotEmpty()) {
            grantedShotModes = emptySet()
            updateShotModeChangeable()
            ShotModePolicy.coerceToAllowed(currentShotMode, allowSingle(), allowBurst(), allowAuto())?.let { setShotMode(it) }
        }
        _state.update { it.copy(overshieldHits = 0, poweredShotMode = null) }
    }

    private fun allowSingle() = config.allowSingleShotMode || GameSettingsRepository.SHOT_MODE_SINGLE in grantedShotModes
    private fun allowBurst() = config.allowBurstShotMode || GameSettingsRepository.SHOT_MODE_BURST in grantedShotModes
    private fun allowAuto() = config.allowAutoShotMode || GameSettingsRepository.SHOT_MODE_FULL_AUTO in grantedShotModes

    private fun updateShotModeChangeable() {
        val allowedModes = listOf(allowSingle(), allowBurst(), allowAuto()).count { it }
        _state.update { it.copy(shotModeChangeable = allowedModes > 1) }
    }

    /** Ends the respawn shield now (it is only good inside the spawn). */
    fun endRespawnShield() {
        if (!isShielded) return
        shieldTimer?.cancel()
        shieldTimer = null
        isShielded = false
        _state.update { it.copy(shieldSeconds = null) }
    }

    fun setOutOfBounds(disabled: Boolean) {
        if (outOfBounds == disabled) return
        outOfBounds = disabled
        if (disabled) {
            if (reloading == RELOADING_NONE) startReload(RELOADING_OUT_OF_BOUNDS)
        } else if (reloading == RELOADING_OUT_OF_BOUNDS) {
            finishReload()
        }
    }

    /** Out of bounds too long, with the health penalty: loses [amount] health, as a hit from nobody. */
    fun applyOutOfBoundsDamage(amount: Int) {
        if (amount <= 0 || config.gameState != GameStateRepository.GAME_STATE_RUNNING) return
        applyDamage(-amount, 0, network?.useNetwork == true)
    }

    /** Out of bounds too long, with the elimination penalty. */
    fun eliminateOutOfBounds() {
        if (config.gameState != GameStateRepository.GAME_STATE_RUNNING) return
        applyDamage(-health, 0, network?.useNetwork == true)
    }

    private fun setShotsRemaining(shotsRemaining: Byte) {
        _state.update { it.copy(shotsRemaining = shotsRemaining) }
        lastShotCount = shotsRemaining
    }

    // ---------------------------------------------------------------------------------------
    // Spawn, shield, game clock
    // ---------------------------------------------------------------------------------------

    private fun startSpawn(eliminatedBy: String) {
        reloadTimer?.cancel()
        shieldTimer?.cancel()
        shieldTimer = null
        spawnTimer?.cancel()
        spawnTimer = null
        isShielded = false
        _state.update { it.copy(shieldSeconds = null) }
        config.gameState = GameStateRepository.GAME_STATE_ELIMINATED
        startReload(RELOADING_ELIMINATED)
        clearHitOverlay()

        if ((hasLivesLimit && eliminationCount <= 0) || noRespawn) {
            _state.update { it.copy(spawn = SpawnPhase.OutOfLives(eliminatedBy)) }
            return
        }

        if (!startingGameLabel && config.manualRespawn) {
            _state.update { it.copy(spawn = SpawnPhase.AwaitingRespawn(eliminatedBy, needsSpawn = respawnHolds.isNotEmpty())) }
            return
        }
        startSpawnCountdown(eliminatedBy)
    }

    /** Manual respawn: the player asked to respawn, so start the respawn countdown. */
    fun startRespawn() {
        val awaiting = _state.value.spawn as? SpawnPhase.AwaitingRespawn ?: return
        if (respawnHolds.isNotEmpty()) {
            // Not in a spawn (or off the field): holding reload does nothing yet
            emit(SessionEffect.Sound(SessionSound.EMPTY))
            return
        }
        startSpawnCountdown(awaiting.eliminatedBy)
    }

    private fun startSpawnCountdown(eliminatedBy: String) {
        val countdownSeconds = if (startingGameLabel) config.startCountdownSeconds else config.respawnTimeSeconds
        _state.update { it.copy(spawn = SpawnPhase.Spawning(countdownSeconds, eliminatedBy, startingGameLabel)) }
        spawnTimer = scope.countDown(clock, countdownSeconds * 1000, SPAWN_TICK_MILLIS,
            onTick = { millisLeft ->
                _state.update { it.copy(spawn = SpawnPhase.Spawning(millisLeft / 1000, eliminatedBy, startingGameLabel)) }
                emit(SessionEffect.Sound(SessionSound.BEEP))
            },
            onFinish = {
                if (respawnHeld) {
                    // Off the field, or not at a spawn yet: respawn once that is fixed
                    _state.update {
                        it.copy(spawn = SpawnPhase.AwaitingReturn(eliminatedBy, startingGameLabel, RespawnHold.OUT_OF_BOUNDS in respawnHolds))
                    }
                } else {
                    spawned()
                }
            })
    }

    private fun spawned() {
        Log.d(TAG, "spawned!")
        startingGameLabel = false
        health = config.fullHealth
        _state.update { it.copy(spawn = SpawnPhase.None, health = health) }
        config.gameState = GameStateRepository.GAME_STATE_RUNNING
        emit(SessionEffect.Sound(SessionSound.SPAWN))
        finishReload()
        if (startGameTimer) {
            startGameTimer = false
            if ((config.gameLimit and GameSettingsRepository.GAME_LIMIT_TIME) != 0) {
                if (!gameTimerRunning) startGameCountdown(config.timeLimitMinutes * 60L)
            } else {
                _state.update { it.copy(clock = GameClock.Chronometer(clock())) }
            }
        } else {
            val shieldTime = config.respawnShieldTimeSeconds
            if (shieldTime > 0) startRespawnShield(shieldTime)
        }
    }

    private fun startRespawnShield(shieldTimeInSeconds: Long) {
        shieldTimer?.cancel()
        shieldTimer = null
        isShielded = true
        _state.update { it.copy(shieldSeconds = shieldTimeInSeconds) }
        shieldTimer = scope.countDown(clock, shieldTimeInSeconds * 1000, SPAWN_TICK_MILLIS,
            onTick = { millisLeft -> _state.update { it.copy(shieldSeconds = millisLeft / 1000 + 1) } },
            onFinish = {
                isShielded = false
                _state.update { it.copy(shieldSeconds = null) }
                shieldTimer = null
            })
    }

    private fun startGameCountdown(timeInSeconds: Long) {
        gameCountdown = scope.countDown(clock, timeInSeconds * 1000, COUNTDOWN_TICK_MILLIS,
            onTick = { millisLeft ->
                val minutes = millisLeft / 60_000
                val seconds = millisLeft / 1000 - minutes * 60
                _state.update { it.copy(clock = GameClock.Countdown(ClockFormat.minutesSeconds(minutes, seconds))) }
            },
            onFinish = {
                Log.d(TAG, "Game time ended!")
                emit(SessionEffect.Toast(SessionToast.GAME_TIME_EXPIRED))
                emit(SessionEffect.Sound(SessionSound.ELIMINATED))
                val net = network
                if (net != null && net.useNetwork) {
                    // The server ends the game for everyone; end it locally if it doesn't.
                    scope.launch {
                        delay(NETWORK_END_GAME_FALLBACK_MILLIS)
                        if (config.gameState != GameStateRepository.GAME_STATE_NONE) emit(SessionEffect.EndGameRequested)
                    }
                } else {
                    emit(SessionEffect.EndGameRequested)
                }
            })
    }

    // ---------------------------------------------------------------------------------------
    // Telemetry
    // ---------------------------------------------------------------------------------------

    private fun processTelemetry(telemetry: GattProtocolHandler.TelemetryData) {
        val shotsRemaining = telemetry.shotsRemaining
        val useNetwork = network?.useNetwork == true
        if (!isCommunicating) {
            isCommunicating = true
            hasConnectedToBlaster = true
            // Reset last counters when we start a new connection
            lastTriggerCount = telemetry.triggerCounter
            lastReloadButtonCount = telemetry.reloadCounter
            lastThumbButtonCount = telemetry.thumbCounter
            lastPowerButtonCount = telemetry.powerCounter
            thumbHeldSince = NOT_HELD
            thumbLongPressed = false
            thumbPressPending = false
            thumbLongPressReleased = false
            reloadHoldTimer?.cancel()
            reloadHoldTimer = null
            setShotMode(currentShotMode)
            scope.launch {
                delay(FIRST_PACKET_TEAM_DELAY_MILLIS)
                if (config.playerId.toInt() != 0) setTeam()
            }
            scope.launch {
                delay(FIRST_PACKET_RECOIL_DELAY_MILLIS)
                applyCurrentRecoilSetting()
            }
            emit(SessionEffect.CommunicationStarted)
        }
        if (telemetry.triggerCounter != lastTriggerCount) {
            lastTriggerCount = telemetry.triggerCounter
            if (shotsRemaining.toInt() == 0 || config.gameState != GameStateRepository.GAME_STATE_RUNNING) {
                emit(SessionEffect.Sound(SessionSound.EMPTY))
                emptyTriggerCount++
                // Auto reload after this many empty trigger pulls (for young players)
                if (emptyTriggerCount >= MAX_EMPTY_TRIGGER_PULLS && config.gameState == GameStateRepository.GAME_STATE_RUNNING) startReload()
            }
        }
        /* Rather than monitor if a button is currently pressed, we monitor the counter, so a
           press toggles exactly once however many packets show the button held down. */
        if (telemetry.reloadCounter != lastReloadButtonCount) {
            lastReloadButtonCount = telemetry.reloadCounter
            if (shotsRemaining != config.fullReload) startReload()
        }
        processThumbButton(telemetry)
        processReloadHold(telemetry)
        if (telemetry.powerCounter != lastPowerButtonCount) {
            lastPowerButtonCount = telemetry.powerCounter
            toggleRecoil()
        }
        val averageBattery = battery.add(telemetry.batteryLevel)
        _state.update { it.copy(battery = BatteryLevelPolicy.level(averageBattery, blasterType)) }

        if (telemetry.hitByPlayer1 != 0) {
            applyGrenadePairing(telemetry.hitByPlayer1, telemetry.rawShotIdByte1, useNetwork)
            applyGrenadePairing(telemetry.hitByPlayer2, telemetry.rawShotIdByte2, useNetwork)
        }
        // The grenade that just eliminated this player can't do it again for a while: its hits
        // are dropped (a second hit in the packet moves up to take the first's place)
        var hitBy1 = telemetry.hitByPlayer1
        var shot1 = telemetry.rawShotIdByte1
        var hitBy2 = telemetry.hitByPlayer2
        var shot2 = telemetry.rawShotIdByte2
        val now = clock()
        // While a round waits to start (or between rounds), nobody can be hit at all
        fun immune(hitBy: Int, shot: Byte) = hitsPaused || (immuneGrenade != null && now < immuneUntil && damagingGrenade(hitBy, shot) == immuneGrenade)
        if (immune(hitBy2, shot2)) hitBy2 = 0
        if (immune(hitBy1, shot1)) {
            hitBy1 = hitBy2
            shot1 = shot2
            hitBy2 = 0
        }
        val grenade = damagingGrenade(hitBy2, shot2) ?: damagingGrenade(hitBy1, shot1)
        val eliminationsBefore = hitEliminations
        val layout = config.teamLayout
        val hit = hitResolver.resolve(hitBy1, shot1, hitBy2, shot2,
            HitResolver.Context(config.gameState != GameStateRepository.GAME_STATE_NONE, isShielded, useNetwork,
                layout.mode.isFreeForAll, networkTeam, health),
            hitLookups(layout))
        if (hit.countedHit) {
            hitsTaken++
            _state.update { it.copy(hitsTaken = hitsTaken) }
        }
        for (message in hit.messages) {
            when (message.kind) {
                HitResolver.Kind.HIT -> network?.sendHit(message.playerId)
                HitResolver.Kind.OUT -> network?.sendOut(message.playerId)
                HitResolver.Kind.SHIELDED -> network?.sendShieldHit(message.playerId)
            }
        }
        if (hit.healthRemoved != 0 && config.gameState == GameStateRepository.GAME_STATE_RUNNING) {
            if (overshield > 0) {
                // An overshield takes the hit instead
                overshield--
                _state.update { it.copy(overshieldHits = overshield) }
                emit(SessionEffect.Sound(SessionSound.HIT_SHIELDED))
            } else {
                applyDamage(hit.healthRemoved, hit.hitById, useNetwork)
            }
        }
        // Eliminated by a grenade: that one grenade can't eliminate this player again for a while
        if (hitEliminations != eliminationsBefore && grenade != null) {
            immuneGrenade = grenade
            immuneUntil = now + GRENADE_IMMUNITY_MILLIS
        }

        /* Since setting player ID is somewhat unreliable, we use this to make sure that we are
           displaying the actual ID that the tagger is currently using. */
        val playerId = telemetry.playerId
        if (lastTeam != playerId) {
            Log.d(TAG, "Player ID changed to $playerId")
            lastTeam = playerId
            if (playerId.toInt() != 0) {
                config.playerId = playerId
                emit(SessionEffect.PlayerIdReported)
            } else if (config.playerId.toInt() != 0) {
                setTeam()
            }
        }

        /* We monitor the last shot count rather than trigger pulls here so we know when to
           update the displayed shots remaining and play shooting sounds. pew! pew! */
        if (lastShotCount != shotsRemaining) {
            setShotsRemaining(shotsRemaining)
            if (shotsRemaining != config.fullReload && reloading == RELOADING_NONE) {
                emit(SessionEffect.Sound(if (currentShotMode == GameSettingsRepository.SHOT_MODE_SINGLE) SessionSound.SHOT else SessionSound.SHOT_AUTO))
                if (useNetwork && lastShotFired < wallClock()) {
                    lastShotFired = wallClock() + HIT_ANIMATION_DURATION_MILLISECONDS
                    network?.sendShotFired()
                }
                if (config.reloadOnEmpty && shotsRemaining.toInt() == 0 && config.gameState != GameStateRepository.GAME_STATE_NONE) startReload()
            }
        }
    }

    /**
     * Thumb button: a short press cycles the shot mode, a hold of [THUMB_LONG_PRESS_MILLIS] emits
     * [SessionEffect.ThumbLongPressed] instead. A tap shorter than the telemetry interval may never
     * show as held, so a count without a hold is still a short press.
     */
    private fun processThumbButton(telemetry: GattProtocolHandler.TelemetryData) {
        val counted = telemetry.thumbCounter != lastThumbButtonCount
        lastThumbButtonCount = telemetry.thumbCounter
        val held = telemetry.buttonsHeld and GattProtocolHandler.RECOIL_BUTTON_THUMB != 0
        var shortPress = false
        if (held) {
            thumbLongPressReleased = false
            val now = clock()
            if (thumbHeldSince == NOT_HELD) {
                thumbHeldSince = now
                thumbLongPressed = false
                thumbPressPending = false
            }
            if (counted && !thumbLongPressed) thumbPressPending = true
            if (!thumbLongPressed && now - thumbHeldSince >= THUMB_LONG_PRESS_MILLIS) {
                thumbLongPressed = true
                thumbPressPending = false
                emit(SessionEffect.ThumbLongPressed)
            }
        } else if (thumbHeldSince != NOT_HELD) {
            // Released: the blaster may count the press now rather than when it went down.
            shortPress = !thumbLongPressed && (thumbPressPending || counted)
            thumbLongPressReleased = thumbLongPressed && !counted
            thumbHeldSince = NOT_HELD
            thumbLongPressed = false
            thumbPressPending = false
        } else {
            shortPress = counted && !thumbLongPressReleased
            thumbLongPressReleased = false
        }
        if (shortPress) cycleShotMode()
    }

    /** Manual respawn: holding reload for [RELOAD_LONG_PRESS_MILLIS] while awaiting respawn starts it. */
    private fun processReloadHold(telemetry: GattProtocolHandler.TelemetryData) {
        val held = telemetry.buttonsHeld and GattProtocolHandler.RECOIL_BUTTON_RELOAD != 0
        if (!held || _state.value.spawn !is SpawnPhase.AwaitingRespawn) {
            reloadHoldTimer?.cancel()
            reloadHoldTimer = null
            return
        }
        // Timed from the press, not from packets: a blaster that only reports when a button
        // changes sends nothing while it is held, and the hold would never reach two seconds
        if (reloadHoldTimer == null) {
            reloadHoldTimer = scope.launch {
                delay(RELOAD_LONG_PRESS_MILLIS)
                reloadHoldTimer = null
                startRespawn()
            }
        }
    }

    private fun applyGrenadePairing(hitBy: Int, shotIdByte: Byte, useNetwork: Boolean) {
        when (GrenadeDecoder.pairingAction(hitBy, shotIdByte)) {
            GrenadeDecoder.PairingAction.PAIR -> {
                // This is a grenade trying to pair
                if (config.pairedGrenadeId.toInt() == 0) {
                    config.pairedGrenadeId = GrenadeDecoder.grenadeId(shotIdByte).toByte()
                    _state.update { it.copy(grenadePaired = true) }
                    if (useNetwork) network?.sendPlayerGrenade()
                    emit(SessionEffect.Toast(SessionToast.GRENADE_PAIRED))
                }
            }
            GrenadeDecoder.PairingAction.UNPAIR -> forgetGrenade()
            GrenadeDecoder.PairingAction.NONE -> Unit
        }
    }

    private fun applyDamage(healthRemoved: Int, hitById: Byte, useNetwork: Boolean) {
        health += healthRemoved
        _state.update { it.copy(health = health) }
        if (health > 0) {
            if (_state.value.hitOverlay == null) {
                // Show the "you're being hit" overlay
                emit(SessionEffect.Vibrate)
                emit(SessionEffect.Sound(SessionSound.HIT))
                val name = if (hitById.toInt() == 0) "" else config.playerName(hitById) ?: ""
                _state.update { it.copy(hitOverlay = name) }
                hitOverlayTimer = scope.launch {
                    delay(HIT_ANIMATION_DURATION_MILLISECONDS)
                    _state.update { it.copy(hitOverlay = null) }
                }
            }
            return
        }
        emit(SessionEffect.Vibrate)
        hitEliminations++
        emit(SessionEffect.Sound(SessionSound.ELIMINATED))
        if (hasLivesLimit) eliminationCount-- else eliminationCount++
        setEliminationDisplay(eliminationCount)
        val eliminatedBy = if (useNetwork) config.playerName(hitById) ?: "" else ""
        // Every elimination is reported, including by our own or a teammate's grenade: the server
        // counts the life lost and decides whether the shooter scores.
        if (useNetwork) network?.sendEliminated(hitById)
        loseDeathPowerUps()
        if (extraLives > 0 && !(hasLivesLimit && eliminationCount <= 0)) {
            // An extra life: back in at once, where you stand
            extraLives--
            health = config.fullHealth
            _state.update { it.copy(health = health, extraLives = extraLives) }
            clearHitOverlay()
            emit(SessionEffect.Sound(SessionSound.SPAWN))
            emit(SessionEffect.Toast(SessionToast.EXTRA_LIFE_USED))
            return
        }
        startSpawn(eliminatedBy)
        if (hasLivesLimit && eliminationCount <= 0) {
            emit(SessionEffect.Toast(SessionToast.OUT_OF_LIVES))
        }
    }

    private fun clearHitOverlay() {
        hitOverlayTimer?.cancel()
        hitOverlayTimer = null
        _state.update { it.copy(hitOverlay = null) }
    }

    private fun setEliminationDisplay(value: Int) {
        _state.update { it.copy(eliminationDisplay = value) }
    }

    private fun emit(effect: SessionEffect) {
        if (!_effects.tryEmit(effect)) Log.w(TAG, "Dropped $effect")
    }

    companion object {
        private const val TAG = "SessionEngine"

        /** After a grenade eliminates a player, it can't again for this long (other grenades still can). */
        const val GRENADE_IMMUNITY_MILLIS = 90_000L
        private const val EFFECT_BUFFER = 64

        private const val RELOADING_NONE = 0
        private const val RELOADING_STARTED = 1
        private const val RELOADING_FINISHING = 2
        private const val RELOADING_ELIMINATED = 3
        private const val RELOADING_OUT_OF_BOUNDS = 4

        /** Automatically reloads after this many empty trigger pulls (for young players). */
        const val MAX_EMPTY_TRIGGER_PULLS = 3

        /** With reload on empty, reloads this short or shorter finish without the reload sound. */
        const val RELOAD_ON_EMPTY_SILENT_MAX_MILLIS = 1000L

        /** §1.6: first packet of a connection → set team after this delay (if a player id is set). */
        const val FIRST_PACKET_TEAM_DELAY_MILLIS = 250L

        /** §1.6: first packet of a connection → current recoil setting after this delay. */
        const val FIRST_PACKET_RECOIL_DELAY_MILLIS = 500L

        /** §1.6: disconnect dialog dismissed → current recoil setting after this delay. */
        const val DISCONNECT_DIALOG_RECOIL_DELAY_MILLIS = 600L

        /** §1.6: game time out on a network game → end locally if the server hasn't after this delay. */
        const val NETWORK_END_GAME_FALLBACK_MILLIS = 3000L

        /** Hit overlay length, also the shot-fired / hit message rate limit (§1.6: 400 ms). */
        const val HIT_ANIMATION_DURATION_MILLISECONDS = 400L

        /** Holding the thumb (mode) button this long is a long press. */
        const val THUMB_LONG_PRESS_MILLIS = 2000L
        /** Holding reload this long while awaiting a manual respawn starts the respawn. */
        const val RELOAD_LONG_PRESS_MILLIS = 2000L
        private const val NOT_HELD = -1L

        private const val SPAWN_TICK_MILLIS = 999L
        private const val COUNTDOWN_TICK_MILLIS = 1000L
    }
}
