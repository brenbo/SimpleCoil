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

package com.simplecoil.simplecoil.domain.map

import com.simplecoil.simplecoil.domain.session.SessionState
import com.simplecoil.simplecoil.domain.session.SpawnPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Walk-back respawn on a player's phone (map design: Respawn points). After an elimination the
 * respawn waits until the player has stood in one of their team's spawns for a few seconds;
 * the existing countdown and manual respawn still apply, whichever finishes last. Moved spawns
 * fade into each other, and the old one keeps working for a grace period. Main thread.
 */
class SpawnController(
    private val field: StateFlow<FieldConfig?>,
    private val session: StateFlow<SessionState>,
    private val actions: Actions,
    /** This player's team (1-4), and whether the game is free for all. */
    private val myTeam: () -> Pair<Int, Boolean>,
    /** True while a game is on (running, or eliminated and waiting to spawn). */
    private val inGame: () -> Boolean,
    scope: CoroutineScope,
    private val clock: () -> Long,
) {
    /** What the blaster session does for spawns. */
    interface Actions {
        fun holdRespawn(held: Boolean)
        fun endShield()
    }

    /** A spawn as the map draws it. */
    data class ShownSpawn(val spawn: Spawn, val radiusMeters: Double, val opacity: Double, val mine: Boolean)

    /** Where to walk to respawn. */
    data class Guide(val target: LatLon, val meters: Double)

    private val _guide = MutableStateFlow<Guide?>(null)

    /** Set while the player needs to walk to a spawn; null otherwise. */
    val guide: StateFlow<Guide?> = _guide.asStateFlow()

    private var current: List<Spawn> = emptyList()
    private var previous: List<Spawn> = emptyList()
    private var movedAt = Long.MIN_VALUE
    private var position: LatLon? = null
    private var positionAt = Long.MIN_VALUE
    private var inSpawnSince: Long? = null
    private var held = false

    init {
        scope.launch { field.collect { onField(it) } }
        scope.launch { session.collect { update() } }
        scope.launch {
            while (true) {
                delay(TICK_MILLIS)
                update()
            }
        }
    }

    /** A GPS fix (accuracy in metres; 0 = unknown). Fixes worse than the field's gate are ignored. */
    fun onFix(point: LatLon, accuracyMeters: Float) {
        val gate = field.value?.rules?.accuracyGateMeters ?: 0.0
        if (gate > 0 && (accuracyMeters <= 0f || accuracyMeters > gate)) return
        position = point
        positionAt = clock()
        update()
    }

    /** The spawns to draw at [nowMillis], fading in and out. */
    fun shown(nowMillis: Long = clock()): List<ShownSpawn> {
        val config = field.value ?: return emptyList()
        val (team, ffa) = myTeam()
        val fade = config.spawnRules.crossfadeSeconds * 1000L
        val progress = if (movedAt == Long.MIN_VALUE || fade <= 0) 1.0 else ((nowMillis - movedAt).toDouble() / fade).coerceIn(0.0, 1.0)
        val radius = config.spawnRules.radiusMeters
        // Only spawns that actually moved fade; one that stayed put stays solid
        return (previous - current.toSet()).map { ShownSpawn(it, radius, 1 - progress, it.isFor(team, ffa)) }.filter { it.opacity > 0 } +
            current.map { ShownSpawn(it, radius, if (it in previous) 1.0 else progress, it.isFor(team, ffa)) }
    }

    /** True while spawns are fading, so the map redraws them every frame. */
    fun isFading(nowMillis: Long = clock()): Boolean {
        val fade = (field.value?.spawnRules?.crossfadeSeconds ?: 0) * 1000L
        return movedAt != Long.MIN_VALUE && nowMillis - movedAt < fade
    }

    private fun onField(config: FieldConfig?) {
        val spawns = config?.spawns ?: emptyList()
        if (spawns != current) {
            previous = current
            current = spawns
            // A move from nothing (the first load) just appears; a real move fades
            movedAt = if (previous.isEmpty()) Long.MIN_VALUE else clock()
        }
        update()
    }

    /** The spawns this player may use now: their team's, including moved ones still in their grace time. */
    private fun usable(config: FieldConfig, nowMillis: Long): List<Spawn> {
        val (team, ffa) = myTeam()
        val grace = config.spawnRules.oldSpawnGraceSeconds * 1000L
        val old = if (movedAt != Long.MIN_VALUE && nowMillis - movedAt < grace) previous else emptyList()
        return (current + old).filter { it.isFor(team, ffa) }
    }

    fun update() {
        val now = clock()
        val config = field.value
        if (config == null || !inGame()) {
            reset()
            return
        }
        val rules = config.spawnRules
        val spawns = usable(config, now)
        val here = position
        val state = session.value

        // The shield is only good inside the spawn
        if (rules.shieldOnlyInSpawn && state.shieldSeconds != null && here != null && spawns.isNotEmpty() &&
            spawns.none { GeoMath.distanceMeters(here, it.point) <= rules.radiusMeters + SHIELD_MARGIN_METERS }) {
            actions.endShield()
        }

        // Without a recent fix (no GPS, or no permission) walking back can't be checked: don't trap the player
        val located = here != null && now - positionAt < POSITION_STALE_MILLIS
        if (!rules.walkBack || spawns.isEmpty() || !located || !needsSpawn(state.spawn, rules)) {
            inSpawnSince = null
            setHeld(false)
            _guide.value = null
            return
        }
        val inside = here != null && spawns.any { GeoMath.distanceMeters(here, it.point) <= rules.radiusMeters }
        if (inside) {
            if (inSpawnSince == null) inSpawnSince = now
        } else {
            inSpawnSince = null
        }
        val since = inSpawnSince
        val arrived = since != null && now - since >= rules.secondsInSpawn * 1000L
        setHeld(!arrived)
        _guide.value = if (arrived || here == null) null else {
            val nearest = spawns.minBy { GeoMath.distanceMeters(here, it.point) }
            Guide(nearest.point, (GeoMath.distanceMeters(here, nearest.point) - rules.radiusMeters).coerceAtLeast(0.0))
        }
    }

    /** Eliminated (or starting, with start at spawn on): the player has to get to a spawn. */
    private fun needsSpawn(phase: SpawnPhase, rules: SpawnRules): Boolean = when (phase) {
        is SpawnPhase.Spawning -> !phase.startingGame || rules.startAtSpawn
        is SpawnPhase.AwaitingReturn -> !phase.startingGame || rules.startAtSpawn
        is SpawnPhase.AwaitingRespawn -> true
        is SpawnPhase.OutOfLives, SpawnPhase.None -> false
    }

    private fun setHeld(hold: Boolean) {
        if (held == hold) return
        held = hold
        actions.holdRespawn(hold)
    }

    private fun reset() {
        inSpawnSince = null
        setHeld(false)
        _guide.value = null
    }

    private companion object {
        const val TICK_MILLIS = 1_000L

        /** A fix older than this doesn't count as knowing where the player is. */
        const val POSITION_STALE_MILLIS = 30_000L

        /** GPS jitter at the spawn's edge doesn't end the shield. */
        const val SHIELD_MARGIN_METERS = 3.0
    }
}
