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

import com.simplecoil.simplecoil.domain.map.Border
import com.simplecoil.simplecoil.domain.map.GeoMath
import com.simplecoil.simplecoil.domain.map.LatLon
import org.json.JSONObject

/** A player as the server's game engines see them: team, where they are (null if not known lately), and in the game or not. */
data class PlayerInfo(val team: Int, val point: LatLon?, val alive: Boolean)

/** Capture the Flag settings (map design: Capture the Flag). */
data class FlagRules(
    val capturesToWin: Int = 3,
    /** How often the carrier shows on the other team's map: 0 = never, 1 = always, else every that many seconds. */
    val carrierShownEverySeconds: Int = 10,
    val droppedReturnsSeconds: Int = 30,
    val ownFlagHomeToScore: Boolean = true,
    val baseRadiusMeters: Double = 10.0,
    /** One neutral flag in the middle of the field instead of one at each team's base. */
    val oneFlag: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("toWin", capturesToWin).put("shownEvery", carrierShownEverySeconds).put("returns", droppedReturnsSeconds)
        .put("ownHome", ownFlagHomeToScore).put("baseRadius", baseRadiusMeters).put("oneFlag", oneFlag)

    fun clamped(): FlagRules = copy(
        capturesToWin = capturesToWin.coerceIn(1, 10),
        carrierShownEverySeconds = carrierShownEverySeconds.coerceIn(0, 120),
        droppedReturnsSeconds = droppedReturnsSeconds.coerceIn(10, 120),
        baseRadiusMeters = baseRadiusMeters.coerceIn(ObjectiveRules.MIN_RADIUS_METERS, 30.0),
    )

    companion object {
        fun fromJson(json: JSONObject?): FlagRules {
            val d = FlagRules()
            if (json == null) return d
            return FlagRules(json.optInt("toWin", d.capturesToWin), json.optInt("shownEvery", d.carrierShownEverySeconds),
                json.optInt("returns", d.droppedReturnsSeconds), json.optBoolean("ownHome", d.ownFlagHomeToScore),
                json.optDouble("baseRadius", d.baseRadiusMeters), json.optBoolean("oneFlag", d.oneFlag)).clamped()
        }
    }
}

/** A team's flag as a game stands. */
data class FlagState(
    val team: Int,
    val base: LatLon,
    /** Where the flag is: at its base, with its carrier, or where it fell. */
    val point: LatLon,
    /** The player carrying it; 0 if nobody. */
    val carrier: Int,
    val dropped: Boolean,
    /** The carrier shows on the other team's map right now. */
    val revealed: Boolean,
    /** The carrier's team; 0 if nobody carries it. */
    val carrierTeam: Int = 0,
) {
    val home: Boolean get() = carrier == 0 && !dropped
}

/**
 * Capture the Flag on the dedicated server (map design: Capture the Flag): each team's flag sits
 * at its base. An enemy walking into the base takes it; walking it back into their own base
 * scores (with their own flag home, if the rules say so). A carrier who is eliminated drops it
 * where they fell; a teammate touching a dropped flag sends it home, and so does time.
 *
 * With one flag ([center] set): a single neutral flag sits at [center]. Either team takes it,
 * scores by carrying it into their own base, and it goes back to the middle. While players from
 * both teams stand on it nobody takes it; from one team, whoever got there first does.
 */
class FlagEngine(
    private val rules: FlagRules,
    private val bases: Map<Int, LatLon>,
    private val startMillis: Long,
    center: LatLon? = null,
) {

    private class Flag(val team: Int, val base: LatLon) {
        var carrier = 0
        var point: LatLon = base
        var droppedAt: Long? = null
    }

    private val neutral = center != null
    private val flags = if (center != null) listOf(Flag(NEUTRAL, center)) else bases.map { (team, base) -> Flag(team, base) }
    private val captures = HashMap<Int, Int>()
    private var winner = 0

    /** One flag: when each player near the flag got there, so the first one takes it. */
    private val nearSince = HashMap<Int, Long>()

    /** [playerId] was eliminated: any flag they carry falls where they were. */
    fun onEliminated(playerId: Int, nowMillis: Long) {
        flags.filter { it.carrier == playerId }.forEach { drop(it, nowMillis) }
    }

    private fun drop(flag: Flag, nowMillis: Long) {
        flag.carrier = 0
        flag.droppedAt = nowMillis
    }

    private fun sendHome(flag: Flag) {
        flag.carrier = 0
        flag.droppedAt = null
        flag.point = flag.base
    }

    private fun take(flag: Flag, id: Int, at: LatLon) {
        flag.carrier = id
        flag.droppedAt = null
        flag.point = at
    }

    fun tick(nowMillis: Long, players: Map<Int, PlayerInfo>): ModeState {
        if (winner == 0) {
            for (flag in flags) {
                val carrier = players[flag.carrier]
                if (flag.carrier != 0) {
                    // A carrier who left the game or is out drops it
                    if (carrier == null || !carrier.alive) drop(flag, nowMillis) else carrier.point?.let { flag.point = it }
                }
                val dropped = flag.droppedAt
                if (dropped != null && nowMillis - dropped >= rules.droppedReturnsSeconds * 1000L) sendHome(flag)
            }
            if (neutral) pickUpNeutral(flags.single(), nowMillis, players) else pickUp(players)
            // Carrying a flag into your own base scores
            for (flag in flags) {
                val carrierId = flag.carrier
                if (carrierId == 0) continue
                val carrier = players[carrierId] ?: continue
                val base = bases[carrier.team] ?: continue
                val at = carrier.point ?: continue
                val home = flags.firstOrNull { it.team == carrier.team }
                val ownHome = home == null || (home.carrier == 0 && home.droppedAt == null)
                if (GeoMath.distanceMeters(at, base) <= rules.baseRadiusMeters && (ownHome || !rules.ownFlagHomeToScore)) {
                    captures[carrier.team] = (captures[carrier.team] ?: 0) + 1
                    sendHome(flag)
                    if ((captures[carrier.team] ?: 0) >= rules.capturesToWin) winner = carrier.team
                }
            }
        }
        val revealed = when (rules.carrierShownEverySeconds) {
            0 -> false
            1 -> true
            // Shown for a few seconds in every interval
            else -> ((nowMillis - startMillis) % (rules.carrierShownEverySeconds * 1000L)) < REVEAL_MILLIS
        }
        return ModeState(
            mode = GameMode.CAPTURE_THE_FLAG, objectives = emptyList(), scores = bases.keys.associateWith { captures[it] ?: 0 },
            scoreToWin = rules.capturesToWin, radiusMeters = rules.baseRadiusMeters, winner = winner, movesAt = 0L,
            flags = flags.map { FlagState(it.team, it.base, it.point, it.carrier, it.droppedAt != null, revealed, players[it.carrier]?.team ?: 0) },
            flagBases = if (neutral) bases else emptyMap(),
        )
    }

    /** A flag per team: an enemy takes it, a teammate touching it where it fell sends it home. */
    private fun pickUp(players: Map<Int, PlayerInfo>) {
        for ((id, p) in players) {
            val at = p.point ?: continue
            if (!p.alive) continue
            for (flag in flags) {
                val near = GeoMath.distanceMeters(at, flag.point) <= rules.baseRadiusMeters
                when {
                    flag.carrier != 0 || !near -> {}
                    flag.droppedAt != null && p.team == flag.team -> sendHome(flag)
                    p.team != flag.team && flags.none { it.carrier == id } -> take(flag, id, at)
                }
            }
        }
    }

    /** The one flag: nobody takes it while both teams are on it; otherwise the first one there does. */
    private fun pickUpNeutral(flag: Flag, nowMillis: Long, players: Map<Int, PlayerInfo>) {
        val near = players.filter { (_, p) ->
            val at = p.point
            p.alive && at != null && GeoMath.distanceMeters(at, flag.point) <= rules.baseRadiusMeters
        }
        nearSince.keys.retainAll(near.keys)
        near.keys.forEach { nearSince.getOrPut(it) { nowMillis } }
        if (flag.carrier != 0 || near.isEmpty()) return
        if (near.values.map { it.team }.toSet().size > 1) return
        val first = near.keys.minWith(compareBy<Int> { nearSince.getValue(it) }.thenBy { it })
        take(flag, first, near.getValue(first).point!!)
    }

    companion object {
        private const val REVEAL_MILLIS = 3_000L

        /** The one flag's "team": nobody's. */
        const val NEUTRAL = 0
    }
}

/**
 * Where the one flag goes when the host hasn't put it down: inside [border], clear of its edge,
 * as near the same distance from both [bases] as can be (fair to both teams) and near the middle
 * between them. Null on a field with no room for it.
 */
fun centerFlagSpot(border: Border, bases: Map<Int, LatLon>, radiusMeters: Double, edgeBufferMeters: Double): LatLon? {
    val b = border.bounds
    val target = if (bases.size >= 2) bases.values.let { v -> LatLon(v.map { it.latitude }.average(), v.map { it.longitude }.average()) } else b.center
    val grid = (0..GRID).flatMap { i ->
        (0..GRID).map { j -> LatLon(b.south + (b.north - b.south) * i / GRID, b.west + (b.east - b.west) * j / GRID) }
    }.filter { border.contains(it) }
    // Room for the flag's circle inside the edge, loosening on a small field
    for (buffer in listOf(edgeBufferMeters + radiusMeters, radiusMeters, 0.0)) {
        val usable = grid.filter { border.distanceToEdgeMeters(it) >= buffer }
        if (usable.isEmpty()) continue
        return usable.minBy { spot ->
            val distances = bases.values.map { GeoMath.distanceMeters(spot, it) }
            val unfair = if (distances.size >= 2) distances.max() - distances.min() else 0.0
            unfair * 2 + GeoMath.distanceMeters(spot, target)
        }
    }
    return null
}

private const val GRID = 40

/** Each team's base: where its flag sits, or with one flag, where the team scores it. */
fun ModeState.flagBaseSpots(): Map<Int, LatLon> = flagBases.ifEmpty { flags.associate { it.team to it.base } }
