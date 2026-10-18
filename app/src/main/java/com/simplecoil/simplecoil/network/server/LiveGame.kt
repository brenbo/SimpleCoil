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
import com.simplecoil.simplecoil.domain.loadout.LoadoutRules
import com.simplecoil.simplecoil.domain.loadout.ShotStats
import com.simplecoil.simplecoil.domain.map.FieldConfig
import com.simplecoil.simplecoil.domain.map.LatLon
import com.simplecoil.simplecoil.domain.map.SpawnPlanner
import com.simplecoil.simplecoil.domain.map.GeoMath
import com.simplecoil.simplecoil.domain.mode.GameMode
import com.simplecoil.simplecoil.domain.mode.BattleRoyaleEngine
import com.simplecoil.simplecoil.domain.mode.ExtractionEngine
import com.simplecoil.simplecoil.domain.mode.FlagEngine
import com.simplecoil.simplecoil.domain.mode.PlayerInfo
import com.simplecoil.simplecoil.domain.mode.flagBases
import com.simplecoil.simplecoil.domain.mode.MarksmanEngine
import com.simplecoil.simplecoil.domain.mode.ModeState
import com.simplecoil.simplecoil.domain.mode.ZonePlan
import com.simplecoil.simplecoil.domain.mode.ModeEngine
import com.simplecoil.simplecoil.domain.mode.Objective
import com.simplecoil.simplecoil.domain.mode.ObjectivePlanner
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
    /** The players in the game, by id, with their team (Battle Royale counts who is left). */
    private val players: () -> Map<Int, Int> = { emptyMap() },
    /** Server wall clock, for drop times. */
    private val wallClock: () -> Long = System::currentTimeMillis,
    /** Monotonic clock, as GPS reports are stamped. */
    private val monoClock: () -> Long = { System.nanoTime() / 1_000_000L },
    private val random: Random = Random.Default,
    /** An objective game was won by [team]: the server ends it. */
    private val onWinner: (team: Int) -> Unit = {},
) {
    val field = LiveField()
    val shots = ShotStats()

    /** The objective game's state as the phones get it; null outside one. */
    @Volatile
    private var modeBytes: ByteArray? = null

    /** Who says they are in the game (alive), by player id, and when they said it (monotonic). */
    private val alive = java.util.concurrent.ConcurrentHashMap<Int, Pair<Boolean, Long>>()

    /** Runs until cancelled: one game's work each time [phases] says a game is running. */
    suspend fun run(phases: Flow<SessionPhase>, eliminations: Flow<Pair<Int, Int>>) = coroutineScope {
        launch { eliminations.collect { (victim, shooter) -> onEliminated(victim, shooter) } }
        imagery.extras = extras
        phases.distinctUntilChanged().collectLatest { phase ->
            if (phase != SessionPhase.RUNNING) {
                // Back to the host's field; the shots stay for the post-game stats
                rules = null
                field.stop()
                publish()
                return@collectLatest
            }
            shots.clear()
            val base = savedField()
            // Shots are measured on any server, with or without a field set up
            rules = base?.loadout ?: LoadoutRules()
            mode = base?.mode ?: GameMode.CLASSIC
            battleRoyale = null
            flags = null
            extraction = null
            modeBytes = null
            alive.clear()
            base ?: return@collectLatest
            // Capture points left to the server are placed for this game, and every phone gets them
            val playing = withObjectives(base)
            field.start(playing)
            publish()
            coroutineScope {
                launch { moveSpawns(playing) }
                launch { dropCrates(playing) }
                launch { pruneDrops() }
                launch { runMode(playing) }
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
                base.objectives.map { it.point to base.objectiveRules.radiusMeters + LiveField.SPAWN_CLEARANCE_METERS } +
                (field.config()?.drops ?: emptyList()).map { it.point to LiveField.DROP_SPACING_METERS }
            val spot = LiveField.dropSpot(border, base.rules.edgeBufferMeters, keepClear, random) ?: continue
            field.drop(kinds[random.nextInt(kinds.size)], spot, wallClock())
            publish()
        }
    }

    /** [base] with its capture points: the host's, or placed now by field size when it left them to the server. */
    private fun withObjectives(base: FieldConfig): FieldConfig {
        val border = base.border
        if (!base.mode.hasObjectives || border == null) return base
        // The host's points, unless Hardpoint has too few to move between
        val enough = if (base.mode == GameMode.HARDPOINT) base.objectives.size >= 2 else base.objectives.isNotEmpty()
        if (enough) return base
        val count = ObjectivePlanner.count(base.mode, border, base.objectiveRules)
        val placed = ObjectivePlanner.place(border, count, base.objectiveRules, base.spawns, base.spawnRules.radiusMeters,
            base.rules.edgeBufferMeters, random)
        return base.copy(objectives = placed)
    }

    /**
     * Domination or Hardpoint (map design: Game modes): once a second, who stands on each point,
     * then the scores; the phones fetch the result. The first team to the score wins and the
     * server ends the game.
     */
    private suspend fun runMode(base: FieldConfig) {
        val tick: () -> ModeState = when (base.mode) {
            GameMode.DOMINATION, GameMode.HARDPOINT -> {
                if (base.objectives.isEmpty()) return
                val engine = ModeEngine(base.mode, base.objectives, base.objectiveRules, wallClock());
                { engine.tick(wallClock(), presence(base.objectives, base.objectiveRules.radiusMeters)) }
            }
            GameMode.BATTLE_ROYALE -> {
                val border = base.border ?: return
                val engine = BattleRoyaleEngine(ZonePlan(border, base.battleRoyale, wallClock(), random))
                battleRoyale = engine;
                { engine.tick(wallClock(), players()) }
            }
            GameMode.MARKSMAN -> {
                val engine = MarksmanEngine(base.marksman);
                { engine.tick(shots.all(), teamOf) }
            }
            GameMode.CAPTURE_THE_FLAG -> {
                val border = base.border ?: return
                val engine = FlagEngine(base.flag, flagBases(border, base.spawns, base.rules.edgeBufferMeters, random), wallClock())
                flags = engine;
                { engine.tick(wallClock(), playerInfos()) }
            }
            GameMode.EXTRACTION -> {
                val border = base.border ?: return
                val avoid = base.spawns.map { it.point to base.spawnRules.radiusMeters + LiveField.SPAWN_CLEARANCE_METERS }
                val engine = ExtractionEngine(base.extraction, border, avoid, wallClock(), random)
                extraction = engine;
                { engine.tick(wallClock(), playerInfos()) }
            }
            GameMode.CLASSIC -> return
        }
        var won = false
        while (true) {
            val state = tick()
            modeBytes = state.toJson().toString().toByteArray(Charsets.UTF_8)
            if (state.winner != 0 && !won) {
                won = true
                onWinner(state.winner)
            }
            delay(MODE_TICK_MILLIS)
        }
    }

    /** How many players of each team stand on each point: in the game, with a fresh position. */
    private fun presence(objectives: List<Objective>, radiusMeters: Double): Map<String, Map<Int, Int>> {
        val now = monoClock()
        val playing = positions().filter { (id, fix) ->
            val said = alive[id]
            // A player who said they are out (in the last few seconds) can't hold a point
            val out = said != null && !said.first && now - said.second <= ALIVE_STALE_MILLIS
            now - fix.atMillis <= ShotStats.MAX_FIX_AGE_MILLIS && !out
        }
        return objectives.associate { o ->
            o.id to playing.entries.filter { GeoMath.distanceMeters(it.value.point, o.point) <= radiusMeters }
                .groupingBy { teamOf(it.key) }.eachCount()
        }
    }

    private suspend fun pruneDrops() {
        while (true) {
            delay(PRUNE_MILLIS)
            if (field.prune(wallClock())) publish()
        }
    }

    /** The game's loadout rules while one runs; null between games. */
    @Volatile
    private var rules: LoadoutRules? = null

    /** The game's mode while one runs, and its Battle Royale. */
    @Volatile
    private var mode = GameMode.CLASSIC

    @Volatile
    private var battleRoyale: BattleRoyaleEngine? = null

    @Volatile
    private var flags: FlagEngine? = null

    @Volatile
    private var extraction: ExtractionEngine? = null

    /** Every player in the game: team, a fresh position (or none), and whether they say they are in. */
    private fun playerInfos(): Map<Int, PlayerInfo> {
        val now = monoClock()
        val fixes = positions()
        return players().mapValues { (id, team) ->
            val fix = fixes[id]?.takeIf { now - it.atMillis <= ShotStats.MAX_FIX_AGE_MILLIS }
            val said = alive[id]
            val out = said != null && !said.first && now - said.second <= ALIVE_STALE_MILLIS
            PlayerInfo(team, fix?.point, !out)
        }
    }

    private fun onEliminated(victimId: Int, shooterId: Int) {
        val rules = rules ?: return
        val fixes = positions()
        val victim = fixes[victimId]
        battleRoyale?.onEliminated(victimId, wallClock())
        flags?.onEliminated(victimId, wallClock())
        extraction?.onEliminated(victimId)
        // Marksman scores by distance, so it always measures
        if (rules.trackShots || mode == GameMode.MARKSMAN) {
            shots.record(shooterId, victimId, fixes[shooterId], victim, monoClock(), rules.maxShotMeters)
        }
        // Scavenger ammo where they fell, if we know where that was
        if (field.isActive && rules.ammo == AmmoMode.SCAVENGER && victim != null &&
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

        override fun mode(playerId: Int, alive: Boolean): ByteArray? {
            this@LiveGame.alive[playerId] = alive to monoClock()
            return modeBytes
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

        /** How often an objective game moves on. */
        const val MODE_TICK_MILLIS = 1_000L

        /** A player's word that they are out counts for this long (they ask every second). */
        const val ALIVE_STALE_MILLIS = 5_000L

        /** Starts [game] in [scope] for as long as the server runs. */
        fun launchIn(scope: CoroutineScope, game: LiveGame, phases: Flow<SessionPhase>, eliminations: Flow<Pair<Int, Int>>) =
            scope.launch { game.run(phases, eliminations) }
    }
}
