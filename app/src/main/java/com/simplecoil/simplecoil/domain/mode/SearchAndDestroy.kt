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

package com.simplecoil.simplecoil.domain.mode

import com.simplecoil.simplecoil.domain.map.GeoMath
import com.simplecoil.simplecoil.domain.map.LatLon
import com.simplecoil.simplecoil.domain.map.Spawn
import org.json.JSONObject
import kotlin.random.Random

/** Search & Destroy settings (map design: Search & Destroy). */
data class BombRules(
    val roundsToWin: Int = 4,
    val roundSeconds: Int = 180,
    val plantSeconds: Int = 10,
    val defuseSeconds: Int = 15,
    val fuseSeconds: Int = 45,
    val sites: Int = 2,
    val swapSides: Boolean = true,
    val siteRadiusMeters: Double = 8.0,
    /** The pause between rounds, while everyone respawns. */
    val betweenRoundsSeconds: Int = 15,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("toWin", roundsToWin).put("round", roundSeconds).put("plant", plantSeconds).put("defuse", defuseSeconds)
        .put("fuse", fuseSeconds).put("sites", sites).put("swap", swapSides).put("radius", siteRadiusMeters).put("between", betweenRoundsSeconds)

    fun clamped(): BombRules = copy(
        roundsToWin = roundsToWin.coerceIn(3, 15),
        roundSeconds = roundSeconds.coerceIn(60, 600),
        plantSeconds = plantSeconds.coerceIn(5, 30),
        defuseSeconds = defuseSeconds.coerceIn(5, 30),
        fuseSeconds = fuseSeconds.coerceIn(20, 90),
        sites = sites.coerceIn(1, 3),
        siteRadiusMeters = siteRadiusMeters.coerceIn(ObjectiveRules.MIN_RADIUS_METERS, 30.0),
        betweenRoundsSeconds = betweenRoundsSeconds.coerceIn(5, 60),
    )

    companion object {
        fun fromJson(json: JSONObject?): BombRules {
            val d = BombRules()
            if (json == null) return d
            return BombRules(json.optInt("toWin", d.roundsToWin), json.optInt("round", d.roundSeconds), json.optInt("plant", d.plantSeconds),
                json.optInt("defuse", d.defuseSeconds), json.optInt("fuse", d.fuseSeconds), json.optInt("sites", d.sites),
                json.optBoolean("swap", d.swapSides), json.optDouble("radius", d.siteRadiusMeters), json.optInt("between", d.betweenRoundsSeconds)).clamped()
        }
    }
}

/** Where a Search & Destroy round stands. */
enum class RoundPhase(val wireValue: String) {
    /** Waiting for the start countdown and for everyone to be back (at their spawn, with walk-back on). */
    STARTING("starting"),
    LIVE("live"), PLANTED("planted"), BETWEEN("between");

    companion object {
        fun fromWire(value: String?): RoundPhase = entries.firstOrNull { it.wireValue == value } ?: LIVE
    }
}

/** Search & Destroy's state for the phones. */
data class BombState(
    val round: Int,
    val attackers: Int,
    val phase: RoundPhase,
    /** When this phase ends: the round timer, the fuse, or the next round (server wall clock). */
    val endsAt: Long,
    /** The attacker carrying the bomb; 0 if nobody. */
    val carrier: Int,
    /** Where the bomb lies, when dropped or planted. */
    val bomb: LatLon?,
    /** The site it was planted at; null if not planted. */
    val plantedAt: String?,
    /** Who won the last round; 0 before the first ends. */
    val lastRoundWinner: Int,
    /** Players out this round. */
    val out: Set<Int>,
)

/**
 * Search & Destroy on the dedicated server (map design: Search & Destroy): rounds with no
 * respawns. One attacker carries the bomb; standing at a site for the plant time plants it, and
 * a defender standing there for the defuse time defuses it. Attackers win a round by the bomb
 * going off or by eliminating every defender; defenders by defusing, by the round running out
 * before a plant, or by eliminating every attacker before one. Teams 1 and 2 swap at half time.
 */
class BombEngine(
    private val rules: BombRules,
    private val sites: List<Objective>,
    startMillis: Long,
    private val random: Random = Random.Default,
    /** The teams' spawns, and whether each round waits for everyone to walk back to theirs. */
    private val spawns: List<Spawn> = emptyList(),
    private val spawnRadiusMeters: Double = 10.0,
    private val walkBack: Boolean = false,
    /** The first round can't go live before this: the phones' start countdown. */
    private val liveAfterMillis: Long = startMillis,
) {

    private var round = 1
    private var phase = RoundPhase.STARTING

    /** In STARTING, when the wait gives up on whoever isn't back; then the round's or the fuse's end. */
    private var endsAt = maxOf(startMillis, liveAfterMillis) + STARTING_MAX_MILLIS
    private val out = HashSet<Int>()
    private var carrier = 0
    private var bomb: LatLon? = null
    private var dropped = false
    private var plantedSite: Objective? = null
    private var dwellPlayer = 0
    private var dwellSince = 0L
    private val roundsWon = HashMap<Int, Int>()
    private var lastRoundWinner = 0
    private var winner = 0
    private val lastPoint = HashMap<Int, LatLon>()

    /** Who attacks this round: team 1 in the first half, team 2 after the swap. */
    fun attackers(round: Int = this.round): Int = if (rules.swapSides && round > rules.roundsToWin - 1) 2 else 1

    private fun defenders() = 3 - attackers()

    fun onEliminated(playerId: Int) {
        if (phase == RoundPhase.BETWEEN || phase == RoundPhase.STARTING) return
        out += playerId
        if (playerId == carrier) {
            carrier = 0
            bomb = lastPoint[playerId]
            dropped = true
        }
    }

    /** [started] is false while the game's own start still waits (everyone at a spawn, with "start at spawn"). */
    fun tick(nowMillis: Long, players: Map<Int, PlayerInfo>, started: Boolean = true): ModeState {
        players.forEach { (id, p) -> p.point?.let { lastPoint[id] = it } }
        if (winner == 0) step(nowMillis, players, started)
        return state()
    }

    private fun step(now: Long, players: Map<Int, PlayerInfo>, started: Boolean) {
        if (phase == RoundPhase.BETWEEN) {
            if (now < endsAt) return
            startRound(now)
        }
        if (phase == RoundPhase.STARTING) {
            // Nobody plays (or plants) before everyone is back: out of the countdown and, with
            // walk-back, at their team's spawn. Whoever never comes back holds it only so long
            if (!started || now < liveAfterMillis) return
            val back = players.all { (_, p) -> p.alive && (!walkBack || atSpawn(p)) }
            if (!back && now < endsAt) return
            phase = RoundPhase.LIVE
            endsAt = now + rules.roundSeconds * 1000L
        }
        val inGame = players.filter { (id, p) -> p.alive && id !in out }
        val attackersLeft = inGame.filterValues { it.team == attackers() }
        val defendersLeft = inGame.filterValues { it.team == defenders() }
        // A team is wiped out when every one of its players was eliminated this round. Not by
        // "not in the game": a player still in the start or respawn countdown says that too
        fun wiped(team: Int) = players.any { it.value.team == team } && players.filter { it.value.team == team }.keys.all { it in out }
        // The bomb goes to an attacker at the start of the round
        if (carrier == 0 && !dropped && phase == RoundPhase.LIVE && attackersLeft.isNotEmpty()) {
            carrier = attackersLeft.keys.toList()[random.nextInt(attackersLeft.size)]
        }
        if (carrier != 0 && carrier !in attackersLeft) {
            bomb = lastPoint[carrier]
            dropped = true
            carrier = 0
        }
        val lying = bomb
        if (dropped && lying != null) {
            attackersLeft.entries.firstOrNull { (_, p) -> p.point?.let { GeoMath.distanceMeters(it, lying) <= rules.siteRadiusMeters } == true }?.let {
                carrier = it.key
                dropped = false
                bomb = null
            }
        }

        when (phase) {
            RoundPhase.LIVE -> {
                val planter = attackersLeft[carrier]
                val site = planter?.point?.let { at -> sites.firstOrNull { GeoMath.distanceMeters(at, it.point) <= rules.siteRadiusMeters } }
                if (site != null && dwell(carrier, now) >= rules.plantSeconds * 1000L) {
                    phase = RoundPhase.PLANTED
                    plantedSite = site
                    bomb = site.point
                    carrier = 0
                    dropped = false
                    endsAt = now + rules.fuseSeconds * 1000L
                    dwellPlayer = 0
                } else if (site == null) {
                    if (dwellPlayer == carrier) dwellPlayer = 0
                }
                when {
                    phase == RoundPhase.PLANTED -> {}
                    wiped(attackers()) -> endRound(defenders(), now)
                    wiped(defenders()) -> endRound(attackers(), now)
                    now >= endsAt -> endRound(defenders(), now)
                }
            }
            RoundPhase.PLANTED -> {
                val site = plantedSite ?: return
                val defuser = defendersLeft.entries.firstOrNull { (_, p) ->
                    p.point?.let { GeoMath.distanceMeters(it, site.point) <= rules.siteRadiusMeters } == true
                }
                when {
                    defuser != null && dwell(defuser.key, now) >= rules.defuseSeconds * 1000L -> endRound(defenders(), now)
                    now >= endsAt -> endRound(attackers(), now)
                    wiped(defenders()) -> endRound(attackers(), now)
                    defuser == null -> dwellPlayer = 0
                }
            }
            RoundPhase.STARTING, RoundPhase.BETWEEN -> {}
        }
    }

    /** True if [p] stands at one of their team's spawns (or the team has none). */
    private fun atSpawn(p: PlayerInfo): Boolean {
        val mine = spawns.filter { it.isFor(p.team, false) }
        if (mine.isEmpty()) return true
        val at = p.point ?: return false
        return mine.any { GeoMath.distanceMeters(at, it.point) <= spawnRadiusMeters }
    }

    /** How long [playerId] has been at it; a new player starts over. */
    private fun dwell(playerId: Int, now: Long): Long {
        if (dwellPlayer != playerId) {
            dwellPlayer = playerId
            dwellSince = now
        }
        return now - dwellSince
    }

    private fun endRound(team: Int, now: Long) {
        roundsWon[team] = (roundsWon[team] ?: 0) + 1
        lastRoundWinner = team
        if ((roundsWon[team] ?: 0) >= rules.roundsToWin) winner = team
        phase = RoundPhase.BETWEEN
        endsAt = now + rules.betweenRoundsSeconds * 1000L
        dwellPlayer = 0
    }

    private fun startRound(now: Long) {
        round++
        phase = RoundPhase.STARTING
        endsAt = now + STARTING_MAX_MILLIS
        out.clear()
        carrier = 0
        bomb = null
        dropped = false
        plantedSite = null
        dwellPlayer = 0
    }

    private fun state(): ModeState {
        val planted = plantedSite
        val doing = if (dwellPlayer != 0) {
            val f = if (phase == RoundPhase.PLANTED) rules.defuseSeconds else rules.plantSeconds
            dwellPlayer to f
        } else null
        return ModeState(
            mode = GameMode.SEARCH_AND_DESTROY,
            objectives = sites.map { s ->
                val here = planted?.id == s.id
                ObjectiveState(s.id, s.point, owner = if (here) attackers() else 0, capturing = 0, progress = 0.0,
                    contested = false, active = true, next = false)
            },
            scores = mapOf(1 to (roundsWon[1] ?: 0), 2 to (roundsWon[2] ?: 0)),
            scoreToWin = rules.roundsToWin, radiusMeters = rules.siteRadiusMeters, winner = winner, movesAt = 0L,
            round = round, noRespawn = true,
            bomb = BombState(round, attackers(), phase, endsAt, carrier, if (dropped || planted != null) bomb else null,
                planted?.id, lastRoundWinner, out.toSet()),
            actor = doing?.first ?: 0,
        )
    }

    companion object {
        /** The longest a round waits for everyone to be back before it starts anyway. */
        const val STARTING_MAX_MILLIS = 60_000L
    }
}
