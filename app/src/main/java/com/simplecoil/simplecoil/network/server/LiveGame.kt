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

package com.simplecoil.simplecoil.network.server

import com.simplecoil.simplecoil.domain.loadout.AmmoMode
import com.simplecoil.simplecoil.domain.loadout.DropKind
import com.simplecoil.simplecoil.domain.loadout.Fix
import com.simplecoil.simplecoil.domain.loadout.LiveField
import com.simplecoil.simplecoil.domain.loadout.ShotStats
import com.simplecoil.simplecoil.domain.map.FieldConfig
import com.simplecoil.simplecoil.domain.map.LatLon
import com.simplecoil.simplecoil.domain.map.SpawnPlanner
import com.simplecoil.simplecoil.network.imagery.FieldImageryServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

/**
 * What the dedicated server does with the field during a game (map design: Moving spawns, Drops,
 * Longest shot): moves the spawns, drops crates, leaves scavenger ammo where players fall,
 * measures each elimination, and decides who gets a drop. Phones see it all through the field
 * service, which serves [LiveField]'s config in place of the host's saved one while a game runs.
 */
class LiveGame(
    private val imagery: FieldImageryServer,
    /** The host's saved field, read when a game starts. */
    private val savedField: suspend () -> FieldConfig?,
    /** Every player's last reported position, by player id. */
    private val positions: () -> Map<Int, Fix>,
    /** A player's team, by id (in free for all, their own id). */
    private val teamOf: (Int) -> Int,
    /** A player's name, for the stats. */
    private val nameOf: (Int) -> String?,
    /** Server wall clock, for drop times. */
    private val wallClock: () -> Long = System::currentTimeMillis,
    /** Monotonic clock, as GPS reports are stamped. */
    private val monoClock: () -> Long = { System.nanoTime() / 1_000_000L },
    private val random: Random = Random.Default,
) {
    val field = LiveField()
    val shots = ShotStats()

    /** Runs until cancelled: one game's work each time [phases] says a game is running. */
    suspend fun run(phases: Flow<SessionPhase>, eliminations: Flow<Pair<Int, Int>>) = coroutineScope {
        launch { eliminations.collect { (victim, shooter) -> onEliminated(victim, shooter) } }
        imagery.extras = extras
        phases.distinctUntilChanged().collectLatest { phase ->
            if (phase != SessionPhase.RUNNING) {
                // Back to the host's field; the shots stay for the post-game stats
                field.stop()
                publish()
                return@collectLatest
            }
            val base = savedField() ?: return@collectLatest
            field.start(base)
            shots.clear()
            publish()
            coroutineScope {
                launch { moveSpawns(base) }
                launch { dropCrates(base) }
                launch { pruneDrops() }
            }
        }
    }

    /** Serves the live field (or, outside a game, the host's saved one again). */
    private fun publish() {
        imagery.liveConfig = field.config()
    }

    private suspend fun moveSpawns(base: FieldConfig) {
        val minutes = base.spawnRules.moveMinutes
        if (minutes <= 0 || base.spawns.isEmpty()) return
        var generation = 0
        while (true) {
            delay(minutes * 60_000L)
            generation++
            val moved = SpawnPlanner.moved(base, field.currentSpawns(), generation, positionsByTeam(), random) ?: continue
            field.moveSpawns(moved.spawns)
            publish()
        }
    }

    private suspend fun dropCrates(base: FieldConfig) {
        val rules = base.loadout
        val border = base.border ?: return
        val kinds = rules.dropKinds.filter { it in DropKind.TIMED }
        if (!rules.dropsEnabled || kinds.isEmpty()) return
        while (true) {
            delay(rules.dropIntervalMinutes * 60_000L)
            val keepClear = field.currentSpawns().map { it.point to base.spawnRules.radiusMeters + LiveField.SPAWN_CLEARANCE_METERS } +
                (field.config()?.drops ?: emptyList()).map { it.point to LiveField.DROP_SPACING_METERS }
            val spot = LiveField.dropSpot(border, base.rules.edgeBufferMeters, keepClear, random) ?: continue
            field.drop(kinds[random.nextInt(kinds.size)], spot, wallClock())
            publish()
        }
    }

    private suspend fun pruneDrops() {
        while (true) {
            delay(PRUNE_MILLIS)
            if (field.prune(wallClock())) publish()
        }
    }

    private fun onEliminated(victimId: Int, shooterId: Int) {
        val config = field.config() ?: return
        val fixes = positions()
        val victim = fixes[victimId]
        if (config.loadout.trackShots) {
            shots.record(shooterId, victimId, fixes[shooterId], victim, monoClock(), config.loadout.maxShotMeters)
        }
        // Scavenger ammo where they fell, if we know where that was
        if (config.loadout.ammo == AmmoMode.SCAVENGER && victim != null &&
            monoClock() - victim.atMillis <= ShotStats.MAX_FIX_AGE_MILLIS) {
            field.scavenge(victim.point, wallClock())
            publish()
        }
    }

    private fun positionsByTeam(): Map<Int, List<LatLon>> =
        positions().entries.groupBy({ teamOf(it.key) }, { it.value.point })

    private val extras = object : FieldImageryServer.Extras {
        override fun claim(dropId: String, playerId: Int): String? {
            val kind = field.claim(dropId, teamOf(playerId), positionsByTeam(), wallClock()) ?: return null
            publish()
            return kind.wireValue
        }

        override fun uav(playerId: Int): ByteArray? {
            if (!field.isActive) return null
            val players = JSONArray()
            positions().forEach { (id, fix) ->
                if (id != playerId) players.put(JSONArray().put(id).put(teamOf(id)).put(fix.point.latitude).put(fix.point.longitude))
            }
            return JSONObject().put("players", players).toString().toByteArray(Charsets.UTF_8)
        }

        override fun stats(): ByteArray? {
            val shot = shots.longest() ?: return null
            val longest = JSONObject()
                .put("shooter", shot.shooterId).put("shooterName", nameOf(shot.shooterId) ?: "")
                .put("victim", shot.victimId).put("victimName", nameOf(shot.victimId) ?: "")
                .put("meters", shot.meters)
                .put("from", JSONArray().put(shot.shooter.latitude).put(shot.shooter.longitude))
                .put("to", JSONArray().put(shot.victim.latitude).put(shot.victim.longitude))
            // Every player's longest, for the scoreboard's ranking
            val players = JSONArray()
            shots.bestByShooter().forEach {
                players.put(JSONArray().put(it.shooterId).put(nameOf(it.shooterId) ?: "").put(it.meters).put(nameOf(it.victimId) ?: ""))
            }
            return JSONObject().put("longest", longest).put("players", players).toString().toByteArray(Charsets.UTF_8)
        }
    }

    companion object {
        const val PRUNE_MILLIS = 5_000L

        /** Starts [game] in [scope] for as long as the server runs. */
        fun launchIn(scope: CoroutineScope, game: LiveGame, phases: Flow<SessionPhase>, eliminations: Flow<Pair<Int, Int>>) =
            scope.launch { game.run(phases, eliminations) }
    }
}
