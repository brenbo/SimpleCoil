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
import com.simplecoil.simplecoil.domain.map.Border
import com.simplecoil.simplecoil.domain.map.FieldConfig
import com.simplecoil.simplecoil.domain.map.GeoCircle
import com.simplecoil.simplecoil.domain.map.LatLon
import com.simplecoil.simplecoil.domain.map.SpawnPlanner
import com.simplecoil.simplecoil.domain.map.Readiness
import com.simplecoil.simplecoil.domain.map.StartGate
import com.simplecoil.simplecoil.domain.map.GeoMath
import com.simplecoil.simplecoil.domain.mode.GameMode
import com.simplecoil.simplecoil.domain.mode.BattleRoyaleEngine
import com.simplecoil.simplecoil.domain.loadout.PostGameMap
import com.simplecoil.simplecoil.domain.postgame.GameRecorder
import com.simplecoil.simplecoil.domain.mode.BombEngine
import com.simplecoil.simplecoil.domain.mode.ExtractionEngine
import com.simplecoil.simplecoil.domain.mode.InfectionEngine
import com.simplecoil.simplecoil.domain.mode.ObjectiveRules
import com.simplecoil.simplecoil.domain.mode.FlagEngine
import com.simplecoil.simplecoil.domain.mode.PlayerInfo
import com.simplecoil.simplecoil.domain.mode.flagBases
import com.simplecoil.simplecoil.domain.mode.MarksmanEngine
import com.simplecoil.simplecoil.domain.mode.ModeState
import com.simplecoil.simplecoil.domain.mode.ZonePlan
import com.simplecoil.simplecoil.domain.mode.ZoneMembership
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
    /** Every player the server knows, connected or not: who is in the game when it starts. */
    private val roster: () -> Set<Int> = { players().keys },
    /** True when the server plays free for all (any spawn is anyone's). */
    private val freeForAll: () -> Boolean = { false },
    /** Server wall clock, for drop times. */
    private val wallClock: () -> Long = System::currentTimeMillis,
    /** Monotonic clock, as GPS reports are stamped. */
    private val monoClock: () -> Long = { System.nanoTime() / 1_000_000L },
    private val random: Random = Random.Default,
    /** The phones' countdown before a game starts. */
    private val startDelayMillis: () -> Long = { 0L },
    /** True while the server shares GPS (its GPS setting isn't Off). */
    private val gpsOn: () -> Boolean = { true },
    /** An objective game was won by [team]: the server ends it. */
    private val onWinner: (team: Int) -> Unit = {},
) {
    val field = LiveField()
    val shots = ShotStats()

    /** The start, with "start at spawn" on: nobody goes until everyone is at their spawn. */
    @Volatile
    private var startGate: StartGate? = null

    @Volatile
    private var startField: FieldConfig? = null

    /** The game as played, for the post-game map. */
    val recorder = GameRecorder()

    /** What the last game's post-game map shows (kept after it ends). */
    @Volatile
    private var postGameMap = PostGameMap.FULL

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
                imagery.noField = false
                recorder.stop(wallClock())
                field.stop()
                publish()
                return@collectLatest
            }
            shots.clear()
            recorder.start(wallClock())
            startGate = null
            startField = null
            // Classic with GPS off is a game without positions: no border, spawns, drops or shot
            // distances, and the phones get no field while it runs (they track nothing)
            val saved = savedField()
            val withoutGps = saved != null && saved.mode == GameMode.CLASSIC && !gpsOn()
            imagery.noField = withoutGps
            val base = saved.takeUnless { withoutGps }
            // Shots are measured on any server, with or without a field set up
            rules = base?.loadout ?: LoadoutRules()
            postGameMap = rules?.postGameMap ?: PostGameMap.FULL
            mode = base?.mode ?: GameMode.CLASSIC
            battleRoyale = null
            flags = null
            extraction = null
            bombs = null
            infection = null
            modeBytes = null
            alive.clear()
            if (base == null) {
                trackPlayers()
                return@collectLatest
            }
            // Capture points left to the server are placed for this game, and every phone gets them
            val playing = withObjectives(base)
            field.start(playing)
            startGate = StartGate(wallClock())
            startField = playing
            publish()
            coroutineScope {
                launch { moveSpawns(playing) }
                launch { dropCrates(playing) }
                launch { pruneDrops() }
                launch { runMode(playing) }
                launch { trackPlayers() }
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
            dropOne(base, border, kinds)
        }
    }

    /** One drop of one of [kinds], somewhere clear on the field; false if no spot turned up. */
    private fun dropOne(base: FieldConfig, border: Border, kinds: List<DropKind>): Boolean {
        val keepClear = field.currentSpawns().map { it.point to base.spawnRules.radiusMeters + LiveField.SPAWN_CLEARANCE_METERS } +
            base.objectives.map { it.point to base.objectiveRules.radiusMeters + LiveField.SPAWN_CLEARANCE_METERS } +
            (field.config()?.drops ?: emptyList()).map { it.point to LiveField.DROP_SPACING_METERS }
        // Battle Royale: inside the zone it is shrinking to, where players will be
        val zone = battleRoyale?.zone(wallClock())
        val spot = (zone?.let { z -> spotInside(border, z.next ?: z.current, keepClear) }
            ?: LiveField.dropSpot(border, base.rules.edgeBufferMeters, keepClear, random)) ?: return false
        field.drop(kinds[random.nextInt(kinds.size)], spot, wallClock())
        publish()
        return true
    }

    // The referee (map design: Dedicated server settings, "during a game")

    /** A player as the referee map shows them. */
    data class RefereePlayer(val id: Int, val name: String, val team: Int, val point: LatLon?, val alive: Boolean)

    /** Everything the referee map shows, now. */
    data class RefereeView(val field: FieldConfig?, val players: List<RefereePlayer>, val mode: ModeState?)

    fun referee(): RefereeView {
        val mode = modeBytes?.let { runCatching { ModeState.fromJson(org.json.JSONObject(String(it, Charsets.UTF_8))) }.getOrNull() }
        val players = playerInfos().map { (id, p) -> RefereePlayer(id, nameOf(id) ?: "", p.team, p.point, p.alive) }
        return RefereeView(field.config(), players.sortedBy { it.team }, mode)
    }

    /** What came of the referee placing a drop. */
    enum class Placed { DROPPED, OUTSIDE, NO_GAME }

    /** The referee drops a [kind] crate at [point]: only on the field, while a game runs. */
    fun dropAt(kind: DropKind, point: LatLon): Placed {
        val border = startField?.border ?: return Placed.NO_GAME
        if (!field.isActive) return Placed.NO_GAME
        if (!border.contains(point)) return Placed.OUTSIDE
        field.drop(kind, point, wallClock()) ?: return Placed.NO_GAME
        publish()
        return Placed.DROPPED
    }

    /** The referee closes the Battle Royale zone now. */
    fun closeZoneNow(): Boolean = battleRoyale?.closeNow(wallClock()) ?: false

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
                // Standing in a point counts after the grace time, and leaving it only past the margin
                val members = ZoneMembership(base.rules.graceSeconds * 1000L, base.rules.leaveMarginMeters);
                { engine.tick(wallClock(), presence(base.objectives, base.objectiveRules.radiusMeters, members)) }
            }
            GameMode.BATTLE_ROYALE -> {
                val border = base.border ?: return
                val engine = BattleRoyaleEngine(ZonePlan(border, base.battleRoyale, wallClock(), random))
                battleRoyale = engine
                var lastPhase = 0;
                {
                    val state = engine.tick(wallClock(), players())
                    val zone = state.zone
                    // Each shrink: spawns move inside the circle it shrinks to, while respawns last
                    if (zone != null && zone.phase > lastPhase) {
                        lastPhase = zone.phase
                        if (!zone.noRespawn) zone.next?.let { moveSpawnsInto(base, it) }
                    }
                    state
                }
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
            GameMode.SEARCH_AND_DESTROY -> {
                val border = base.border ?: return
                val rules = base.bomb
                // The host's points as sites, else sites placed by the rules, clear of the spawns
                val sites = base.objectives.take(rules.sites).takeIf { it.size == rules.sites }
                    ?: ObjectivePlanner.place(border, rules.sites, ObjectiveRules(radiusMeters = rules.siteRadiusMeters), base.spawns,
                        base.spawnRules.radiusMeters, base.rules.edgeBufferMeters, random)
                val start = wallClock()
                val engine = BombEngine(rules, sites, start, random, base.spawns, base.spawnRules.radiusMeters, base.spawnRules.walkBack,
                    liveAfterMillis = start + startDelayMillis())
                bombs = engine;
                {
                    val players = playerInfos()
                    // With "start at spawn", the first round waits for the game's own start
                    val started = startGate?.check(wallClock(), startField, players, freeForAll())?.ready ?: true
                    engine.tick(wallClock(), players, started)
                }
            }
            GameMode.INFECTION -> {
                val engine = InfectionEngine(base.infection, wallClock(), random)
                infection = engine;
                { engine.tick(wallClock(), playerInfos()) }
            }
            GameMode.CLASSIC -> return
        }
        var won = false
        // Who started the game: every player the server had when it started (connected or not, so
        // a phone that drops and comes back keeps its place). Anyone new after that is a late
        // joiner, whom the mode may sit out
        val starters = roster().toHashSet()
        val late = HashSet<Int>()
        while (true) {
            for (id in players().keys) {
                if (id in starters || id in late) continue
                late += id
                bombs?.onLateJoin(id)
                battleRoyale?.onLateJoin(id)
                infection?.onLateJoin(id)
            }
            val state = tick()
            modeBytes = state.toJson().toString().toByteArray(Charsets.UTF_8)
            if (state.winner != 0 && !won) {
                won = true
                onWinner(state.winner)
            }
            delay(MODE_TICK_MILLIS)
        }
    }

    /** A drop spot inside [circle] and on the field, clear of [keepClear]. */
    private fun spotInside(border: Border, circle: GeoCircle, keepClear: List<Pair<LatLon, Double>>): LatLon? {
        val area = Border.Circle(circle.center, circle.radiusMeters)
        repeat(TRIES) {
            LiveField.dropSpot(area, circle.radiusMeters * 0.15, keepClear, random)?.takeIf { border.contains(it) }?.let { return it }
        }
        return circle.center.takeIf { border.contains(it) }
    }

    /** Battle Royale: the spawns move inside [circle], where the next shrink leaves room. */
    private fun moveSpawnsInto(base: FieldConfig, circle: GeoCircle) {
        val current = field.currentSpawns()
        if (current.isEmpty()) return
        val teams = current.map { it.team }.distinct().sorted()
        val perTeam = current.groupingBy { it.team }.eachCount().values.max().coerceIn(1, 4)
        val placed = SpawnPlanner.place(Border.Circle(circle.center, circle.radiusMeters), teams,
            base.spawnRules.copy(perTeam = perTeam), 0.0, positionsByTeam(), current, random).spawns
        if (placed.isEmpty()) return
        field.moveSpawns(placed)
        publish()
    }

    /** How many players of each team stand on each point: in the game, with a fresh position. */
    private fun presence(objectives: List<Objective>, radiusMeters: Double, members: ZoneMembership): Map<String, Map<Int, Int>> {
        val now = monoClock()
        val fixes = positions()
        val ids = fixes.keys + players().keys
        // Fresh positions of players in the game; a player who said they are out (lately) can't hold a point
        val usable = ids.associateWith { id ->
            val fix = fixes[id]
            val said = alive[id]
            val out = said != null && !said.first && now - said.second <= ALIVE_STALE_MILLIS
            fix?.takeIf { now - it.atMillis <= ShotStats.MAX_FIX_AGE_MILLIS && !out }?.point
        }
        return objectives.associate { o ->
            o.id to usable.filter { (id, point) ->
                members.update(id, o.id, point?.let { GeoMath.distanceMeters(it, o.point) }, radiusMeters, now)
            }.keys.groupingBy { teamOf(it) }.eachCount()
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

    @Volatile
    private var bombs: BombEngine? = null

    @Volatile
    private var infection: InfectionEngine? = null

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
        bombs?.onEliminated(victimId)
        infection?.onEliminated(victimId, shooterId)
        // Marksman scores by distance, so it always measures
        if (rules.trackShots || mode == GameMode.MARKSMAN) {
            shots.record(shooterId, victimId, fixes[shooterId], victim, monoClock(), rules.maxShotMeters)?.let {
                recorder.kill(it.shooterId, it.victimId, it.shooter, it.victim, it.meters, wallClock())
            }
        }
        // Scavenger ammo where they fell, if we know where that was
        if (field.isActive && rules.ammo == AmmoMode.SCAVENGER && victim != null &&
            monoClock() - victim.atMillis <= ShotStats.MAX_FIX_AGE_MILLIS) {
            field.scavenge(victim.point, wallClock())
            publish()
        }
    }

    /** The post-game map (map design: post-game stats): everyone's name and side, and with the full map their position, every few seconds. */
    private suspend fun trackPlayers() {
        while (true) {
            val now = monoClock()
            val fixes = positions()
            val withPaths = postGameMap == PostGameMap.FULL
            recorder.sample(wallClock(), players().mapValues { (id, team) ->
                val point = if (withPaths) fixes[id]?.takeIf { now - it.atMillis <= ShotStats.MAX_FIX_AGE_MILLIS }?.point else null
                Triple(nameOf(id) ?: "", team, point)
            })
            delay(TRACK_MILLIS)
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

        override fun ready(): ByteArray? {
            val gate = startGate ?: return Readiness(true, 0, 0).toJson().toString().toByteArray(Charsets.UTF_8)
            return gate.check(wallClock(), startField, playerInfos(), freeForAll()).toJson().toString().toByteArray(Charsets.UTF_8)
        }

        override fun replay(): ByteArray? {
            if (postGameMap == PostGameMap.OFF) return null
            return recorder.record(wallClock(), withTrack = postGameMap == PostGameMap.FULL)?.toJson()?.toString()?.toByteArray(Charsets.UTF_8)
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

        /** Tries for a drop spot inside the Battle Royale zone. */
        private const val TRIES = 20

        /** How often the post-game map samples everyone's position. */
        const val TRACK_MILLIS = 3_000L

        /** A player's word that they are out counts for this long (they ask every second). */
        const val ALIVE_STALE_MILLIS = 5_000L

        /** Starts [game] in [scope] for as long as the server runs. */
        fun launchIn(scope: CoroutineScope, game: LiveGame, phases: Flow<SessionPhase>, eliminations: Flow<Pair<Int, Int>>) =
            scope.launch { game.run(phases, eliminations) }
    }
}
