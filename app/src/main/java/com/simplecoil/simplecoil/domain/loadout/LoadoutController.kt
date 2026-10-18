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

package com.simplecoil.simplecoil.domain.loadout

import com.simplecoil.simplecoil.data.repository.GameSettingsRepository
import com.simplecoil.simplecoil.domain.map.FieldConfig
import com.simplecoil.simplecoil.domain.map.GeoMath
import com.simplecoil.simplecoil.domain.map.LatLon
import com.simplecoil.simplecoil.domain.session.SessionSound
import com.simplecoil.simplecoil.domain.session.SessionState
import com.simplecoil.simplecoil.domain.session.SpawnPhase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A player's loadout on their phone (map design: Ammo, drops and power-ups; Longest shot):
 * limited ammo each life and refills at the spawn; claiming drops by standing on them; power-ups
 * from drops and killstreaks; the UAV; and the longest shot when the game ends. Main thread.
 */
class LoadoutController(
    private val field: StateFlow<FieldConfig?>,
    private val session: StateFlow<SessionState>,
    private val blaster: Blaster,
    private val server: Server,
    /** This player's id and team (in free for all, their own id). */
    private val me: () -> Pair<Int, Int>,
    /** True while a game is on (running, or eliminated and waiting to spawn). */
    private val inGame: () -> Boolean,
    /** True while the player stands in one of their own spawns. */
    private val inOwnSpawn: () -> Boolean,
    private val feedback: Feedback,
    /** The server's eliminations credited to this player ("you eliminated..."). */
    kills: Flow<Unit>,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val clock: () -> Long,
) {
    /** What the blaster session does for the loadout. */
    interface Blaster {
        fun setAmmoReserve(magazines: Int?)
        fun grantShotMode(shotMode: Int)
        fun healFull()
        fun addOvershield(hits: Int)
        fun addExtraLife()
    }

    /** What the server answers; each blocks, and is called off the main thread. */
    interface Server {
        /** The server's clock now, as this phone best knows it. */
        fun now(): Long
        fun claim(dropId: String, playerId: Int): DropKind?
        fun uav(playerId: Int): List<UavPlayer>?
        fun longestShot(): LongestShot?

        /** Fetch the field again now, so a claimed drop goes from every map soon. Main thread. */
        fun refresh()
    }

    interface Feedback {
        fun play(sound: SessionSound)
        fun vibrate()
    }

    /** Something to tell the player about. */
    sealed interface Notice {
        data class DropIncoming(val kind: DropKind) : Notice
        data class Claimed(val kind: DropKind) : Notice
        data class Killstreak(val kind: DropKind) : Notice
    }

    /** A drop the player is standing on: how long until it's theirs. */
    data class Claiming(val drop: Drop, val secondsLeft: Int)

    private val _notices = MutableSharedFlow<Notice>(extraBufferCapacity = 8)
    val notices: SharedFlow<Notice> = _notices.asSharedFlow()

    private val _claiming = MutableStateFlow<Claiming?>(null)
    val claiming: StateFlow<Claiming?> = _claiming.asStateFlow()

    private val _uav = MutableStateFlow<List<UavPlayer>>(emptyList())

    /** Enemies a UAV shows right now; empty when no UAV is up. */
    val uav: StateFlow<List<UavPlayer>> = _uav.asStateFlow()

    private val _longestShot = MutableStateFlow<LongestShot?>(null)

    /** The last game's longest shot, after it ends; null during a game. */
    val longestShot: StateFlow<LongestShot?> = _longestShot.asStateFlow()

    private var position: LatLon? = null
    private var wasInGame = false
    private var wasAlive = false
    private var streak = 0
    private var seenDrops = HashSet<String>()
    private var standingOn: String? = null
    private var standingSince = 0L
    private val triedAt = HashMap<String, Long>()

    /** Drops this player got: off their map at once, before the server's next config says so. */
    private val claimed = HashSet<String>()
    private var claimJob: Job? = null
    private var uavJob: Job? = null

    init {
        scope.launch { session.collect { update() } }
        scope.launch { field.collect { update() } }
        scope.launch { kills.collect { onKill() } }
        scope.launch {
            while (true) {
                delay(TICK_MILLIS)
                update()
            }
        }
    }

    /** A fresh GPS fix. */
    fun onFix(point: LatLon) {
        position = point
        update()
    }

    /** The drops this player can see: all of them, or with "nearest team only", those nearest their team's spawn. */
    fun visibleDrops(): List<Drop> {
        val config = field.value ?: return emptyList()
        val drops = config.drops.filter { it.id !in claimed }
        if (!config.loadout.dropsNearestTeamOnly || config.spawns.isEmpty()) return drops
        val team = me().second
        return drops.filter { drop ->
            if (drop.kind == DropKind.SCAVENGE) return@filter true
            val nearest = config.spawns.minByOrNull { GeoMath.distanceMeters(it.point, drop.point) } ?: return@filter true
            nearest.team == team || nearest.team == 0
        }
    }

    fun update() {
        val config = field.value
        val now = clock()
        val playing = inGame()
        val state = session.value
        val alive = playing && state.spawn == SpawnPhase.None
        val rules = config?.loadout ?: LoadoutRules()
        val limited = rules.ammo != AmmoMode.UNLIMITED

        if (playing && !wasInGame) {
            // A new game: fresh ammo, no streak, no last game's stats
            streak = 0
            seenDrops.clear()
            triedAt.clear()
            claimed.clear()
            _longestShot.value = null
            blaster.setAmmoReserve(if (limited) rules.startingReserve else null)
        }
        if (!playing && wasInGame) onGameOver(rules)
        if (playing && alive && !wasAlive && wasInGame) {
            // Respawned: a new life, a full reserve
            if (limited) blaster.setAmmoReserve(rules.startingReserve)
        }
        if (playing && !alive && wasAlive) streak = 0
        wasInGame = playing
        wasAlive = alive
        if (!playing || config == null) {
            stopClaiming()
            return
        }

        // Refill at your own spawn
        if (alive && limited && rules.refillAtSpawn && (state.reserve ?: 0) < rules.startingReserve && inOwnSpawn()) {
            blaster.setAmmoReserve(rules.startingReserve)
            feedback.play(SessionSound.RELOAD)
        }

        val drops = visibleDrops()
        for (drop in drops) {
            if (seenDrops.add(drop.id) && drop.kind != DropKind.SCAVENGE) {
                feedback.vibrate()
                feedback.play(SessionSound.DROP_INCOMING)
                _notices.tryEmit(Notice.DropIncoming(drop.kind))
            }
        }
        claimIfStanding(drops, rules, alive, now)
    }

    private fun claimIfStanding(drops: List<Drop>, rules: LoadoutRules, alive: Boolean, now: Long) {
        val here = position
        val serverNow = server.now()
        val on = if (!alive || here == null) null else drops.firstOrNull { drop ->
            drop.isLanded(serverNow) && !drop.isExpired(serverNow) &&
                GeoMath.distanceMeters(here, drop.point) <= rules.claimRadiusMeters &&
                (triedAt[drop.id]?.let { now - it >= RETRY_MILLIS } ?: true)
        }
        if (on == null) {
            stopClaiming()
            return
        }
        if (standingOn != on.id) {
            standingOn = on.id
            standingSince = now
        }
        val left = rules.claimSeconds * 1000L - (now - standingSince)
        _claiming.value = Claiming(on, ((left + 999) / 1000).toInt().coerceAtLeast(0))
        if (left > 0 || claimJob?.isActive == true) return
        triedAt[on.id] = now
        val playerId = me().first
        claimJob = scope.launch {
            val kind = withContext(io) { server.claim(on.id, playerId) }
            _claiming.value = null
            standingOn = null
            if (kind != null) {
                claimed += on.id
                apply(kind, rules)
            }
            // Granted or not, the field has likely changed (it's gone, or someone else got it)
            server.refresh()
        }
    }

    private fun stopClaiming() {
        standingOn = null
        _claiming.value = null
    }

    private fun onKill() {
        val rules = field.value?.loadout ?: return
        if (!inGame() || !rules.killstreaks) return
        streak++
        val earned = ArrayList<DropKind>()
        if (rules.uavKills > 0 && streak == rules.uavKills) earned += DropKind.UAV
        if (rules.fullAutoKills > 0 && streak == rules.fullAutoKills) earned += DropKind.SUPPLY
        if (earned.isEmpty()) return
        for (kind in earned) {
            _notices.tryEmit(Notice.Killstreak(kind))
            apply(kind, rules, quietly = true, sound = false)
        }
        // The killstreak, then the UAV coming online. A moment later, and only if the game goes
        // on: the kill that ends a game is followed by the end of the game, not a reward
        scope.launch {
            delay(KILLSTREAK_SOUND_DELAY_MILLIS)
            if (!inGame()) return@launch
            feedback.play(SessionSound.KILLSTREAK)
            if (DropKind.UAV in earned) feedback.play(SessionSound.UAV)
        }
    }

    /** Gives the player what [kind] brings, with its sound unless [sound] is false (a killstreak plays its own). */
    fun apply(kind: DropKind, rules: LoadoutRules, quietly: Boolean = false, sound: Boolean = true) {
        when (kind) {
            DropKind.SUPPLY -> blaster.grantShotMode(GameSettingsRepository.SHOT_MODE_FULL_AUTO)
            DropKind.BURST -> blaster.grantShotMode(GameSettingsRepository.SHOT_MODE_BURST)
            DropKind.AMMO -> if (rules.ammo != AmmoMode.UNLIMITED) blaster.setAmmoReserve(rules.startingReserve)
            DropKind.MEDKIT -> blaster.healFull()
            DropKind.EXTRA_LIFE -> blaster.addExtraLife()
            DropKind.OVERSHIELD -> blaster.addOvershield(rules.overshieldHits)
            DropKind.UAV -> startUav(rules, sound)
            DropKind.SCAVENGE -> {
                val now = session.value.reserve
                if (now != null) blaster.setAmmoReserve(now + rules.scavengeMagazines)
            }
        }
        // The UAV plays its own as it comes online
        if (sound && kind != DropKind.UAV) feedback.play(if (kind == DropKind.SCAVENGE) SessionSound.LOOT else SessionSound.POWER_UP)
        feedback.vibrate()
        if (!quietly) _notices.tryEmit(Notice.Claimed(kind))
    }

    private fun startUav(rules: LoadoutRules, sound: Boolean) {
        uavJob?.cancel()
        if (sound) feedback.play(SessionSound.UAV)
        val playerId = me().first
        val until = clock() + rules.uavSeconds * 1000L
        uavJob = scope.launch {
            while (clock() < until) {
                val players = withContext(io) { server.uav(playerId) }
                val myTeam = me().second
                if (players != null) _uav.value = players.filter { it.team != myTeam }
                delay(UAV_REFRESH_MILLIS)
            }
            _uav.value = emptyList()
        }
    }

    private fun onGameOver(rules: LoadoutRules) {
        uavJob?.cancel()
        _uav.value = emptyList()
        stopClaiming()
        blaster.setAmmoReserve(null)
        if (!rules.longestShotAward) return
        scope.launch {
            // Give the server a moment to count the last elimination
            delay(STATS_DELAY_MILLIS)
            val shot = withContext(io) { server.longestShot() }
            _longestShot.value = shot
        }
    }

    companion object {
        const val TICK_MILLIS = 1_000L
        const val RETRY_MILLIS = 5_000L
        const val UAV_REFRESH_MILLIS = 3_000L
        const val STATS_DELAY_MILLIS = 2_000L

        /** Long enough for the end of the game to arrive after the kill that ends it. */
        const val KILLSTREAK_SOUND_DELAY_MILLIS = 1_500L
    }
}
