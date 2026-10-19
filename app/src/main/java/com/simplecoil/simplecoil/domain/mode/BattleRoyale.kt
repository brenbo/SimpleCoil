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
import com.simplecoil.simplecoil.domain.map.GeoCircle
import com.simplecoil.simplecoil.domain.map.GeoMath
import com.simplecoil.simplecoil.domain.map.LatLon
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** Battle Royale settings (map design: Battle Royale). Every value is a server setting. */
data class BattleRoyaleRules(
    val phases: Int = 4,
    val firstWaitSeconds: Int = 180,
    val shrinkSeconds: Int = 60,
    val pauseSeconds: Int = 120,
    val finalDiameterMeters: Double = 20.0,
    val damagePerTick: Int = 1,
    val tickSeconds: Int = 3,
    /** Each phase hurts more: the damage times the phase. */
    val damageRises: Boolean = true,
    /** Eliminated players respawn until this phase starts shrinking; 0 = never. */
    val respawnUntilPhase: Int = 3,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("phases", phases)
        .put("firstWait", firstWaitSeconds)
        .put("shrink", shrinkSeconds)
        .put("pause", pauseSeconds)
        .put("finalDiameter", finalDiameterMeters)
        .put("damage", damagePerTick)
        .put("tick", tickSeconds)
        .put("rises", damageRises)
        .put("respawnUntil", respawnUntilPhase)

    fun clamped(): BattleRoyaleRules = copy(
        phases = phases.coerceIn(2, 6),
        firstWaitSeconds = firstWaitSeconds.coerceIn(60, 600),
        shrinkSeconds = shrinkSeconds.coerceIn(30, 300),
        pauseSeconds = pauseSeconds.coerceIn(30, 300),
        finalDiameterMeters = finalDiameterMeters.coerceIn(12.0, 50.0),
        damagePerTick = damagePerTick.coerceIn(1, 10),
        tickSeconds = tickSeconds.coerceIn(1, 10),
        respawnUntilPhase = respawnUntilPhase.coerceIn(0, phases.coerceIn(2, 6)),
    )

    companion object {
        fun fromJson(json: JSONObject?): BattleRoyaleRules {
            val d = BattleRoyaleRules()
            if (json == null) return d
            return BattleRoyaleRules(
                phases = json.optInt("phases", d.phases),
                firstWaitSeconds = json.optInt("firstWait", d.firstWaitSeconds),
                shrinkSeconds = json.optInt("shrink", d.shrinkSeconds),
                pauseSeconds = json.optInt("pause", d.pauseSeconds),
                finalDiameterMeters = json.optDouble("finalDiameter", d.finalDiameterMeters),
                damagePerTick = json.optInt("damage", d.damagePerTick),
                tickSeconds = json.optInt("tick", d.tickSeconds),
                damageRises = json.optBoolean("rises", d.damageRises),
                respawnUntilPhase = json.optInt("respawnUntil", d.respawnUntilPhase),
            ).clamped()
        }
    }
}

/** Marksman settings (map design: Marksman): score is the distance of each elimination. */
data class MarksmanRules(
    /** Eliminations shorter than this score nothing. */
    val minimumMeters: Double = 0.0,
    /** First to this total distance wins; 0 = play to the game limits. */
    val distanceToWinMeters: Double = 0.0,
) {
    fun toJson(): JSONObject = JSONObject().put("minimum", minimumMeters).put("toWin", distanceToWinMeters)

    fun clamped(): MarksmanRules = copy(
        minimumMeters = minimumMeters.coerceIn(0.0, 50.0),
        distanceToWinMeters = distanceToWinMeters.coerceIn(0.0, 100_000.0),
    )

    companion object {
        fun fromJson(json: JSONObject?): MarksmanRules {
            val d = MarksmanRules()
            if (json == null) return d
            return MarksmanRules(json.optDouble("minimum", d.minimumMeters), json.optDouble("toWin", d.distanceToWinMeters)).clamped()
        }
    }
}

/** The zone as it stands: where it is now, where it goes next, and how it hurts. */
data class ZoneState(
    val current: GeoCircle,
    /** Where it shrinks to next; null after the last phase. */
    val next: GeoCircle?,
    /** Shrinks begun so far (0 before the first). */
    val phase: Int,
    val phases: Int,
    val shrinking: Boolean,
    /** When it next starts or stops shrinking (server wall clock); 0 when it never will again. */
    val changesAt: Long,
    /** Health lost every [tickSeconds] outside it, this phase. */
    val damage: Int,
    val tickSeconds: Int,
    /** Eliminated players stay out now. */
    val noRespawn: Boolean,
)

/**
 * The zone's whole plan, made once when the game starts (map design: Battle Royale): circles
 * that shrink from one around the field to [BattleRoyaleRules.finalDiameterMeters], each new
 * centre random but inside the last circle, and on the field.
 */
class ZonePlan(border: Border, private val rules: BattleRoyaleRules, private val startMillis: Long, random: Random = Random.Default) {

    /** circles[0] around the field, circles[phases] the final one. */
    val circles: List<GeoCircle>

    init {
        val start = GeoMath.enclosingCircle(border.ring()) ?: GeoCircle(border.bounds.center, 50.0)
        val finalRadius = (rules.finalDiameterMeters / 2).coerceAtMost(start.radiusMeters)
        val list = ArrayList<GeoCircle>()
        list += start
        for (i in 1..rules.phases) {
            // Shrinking by the same share each phase
            val radius = start.radiusMeters * (finalRadius / start.radiusMeters).pow(i.toDouble() / rules.phases)
            val previous = list.last()
            val room = (previous.radiusMeters - radius).coerceAtLeast(0.0)
            list += GeoCircle(centerInside(border, previous.center, room, random), radius)
        }
        circles = list
    }

    /** A random spot up to [room] metres from [center], on the field if one turns up. */
    private fun centerInside(border: Border, center: LatLon, room: Double, random: Random): LatLon {
        repeat(TRIES) {
            // Uniform over the disc
            val r = room * sqrt(random.nextDouble())
            val a = random.nextDouble() * 2 * PI
            val spot = GeoMath.offset(center, r * cos(a), r * sin(a))
            if (border.contains(spot)) return spot
        }
        return center
    }

    private fun shrinkStart(phase: Int): Long =
        startMillis + rules.firstWaitSeconds * 1000L + (phase - 1) * (rules.shrinkSeconds + rules.pauseSeconds) * 1000L

    private fun shrinkEnd(phase: Int): Long = shrinkStart(phase) + rules.shrinkSeconds * 1000L

    /** The zone at [nowMillis]. */
    fun at(nowMillis: Long): ZoneState {
        var phase = 0
        var shrinking = false
        var current = circles[0]
        var changesAt = shrinkStart(1)
        for (i in 1..rules.phases) {
            if (nowMillis < shrinkStart(i)) break
            phase = i
            if (nowMillis < shrinkEnd(i)) {
                // Part way from the last circle to this one
                val f = (nowMillis - shrinkStart(i)).toDouble() / (rules.shrinkSeconds * 1000.0)
                current = lerp(circles[i - 1], circles[i], f)
                shrinking = true
                changesAt = shrinkEnd(i)
            } else {
                current = circles[i]
                shrinking = false
                changesAt = if (i < rules.phases) shrinkStart(i + 1) else 0L
            }
        }
        val next = when {
            shrinking -> circles[phase]
            phase < rules.phases -> circles[phase + 1]
            else -> null
        }
        val damage = rules.damagePerTick * if (rules.damageRises) phase.coerceAtLeast(1) else 1
        val noRespawn = rules.respawnUntilPhase == 0 || phase >= rules.respawnUntilPhase
        return ZoneState(current, next, phase, rules.phases, shrinking, changesAt, damage, rules.tickSeconds, noRespawn)
    }

    private fun lerp(a: GeoCircle, b: GeoCircle, f: Double): GeoCircle = GeoCircle(
        LatLon(a.center.latitude + (b.center.latitude - a.center.latitude) * f, a.center.longitude + (b.center.longitude - a.center.longitude) * f),
        a.radiusMeters + (b.radiusMeters - a.radiusMeters) * f,
    )

    private companion object {
        const val TRIES = 200
    }
}

/**
 * Who is still in a Battle Royale (map design: Battle Royale): a player eliminated while
 * respawns are off is out; the last team with anyone left wins. Plain logic, driven by the
 * server once a second.
 */
class BattleRoyaleEngine(private val plan: ZonePlan) {
    /** How far the referee has moved the zone's clock on (Close the zone now). */
    private var shift = 0L

    /** The zone at [nowMillis], its times on the real clock. */
    fun zone(nowMillis: Long): ZoneState = plan.at(nowMillis + shift).let { z ->
        if (z.changesAt == 0L) z else z.copy(changesAt = z.changesAt - shift)
    }

    /** The referee closes the zone now: the next shrink starts at once. False if none is left, or one is under way. */
    fun closeNow(nowMillis: Long): Boolean {
        val z = plan.at(nowMillis + shift)
        if (z.shrinking || z.changesAt == 0L) return false
        shift += z.changesAt - (nowMillis + shift)
        return true
    }

    private val out = HashSet<Int>()
    private var winner = 0
    private var teamsSeen = 0

    /** [playerId] was eliminated at [nowMillis]; out for good if respawns are over. */
    fun onEliminated(playerId: Int, nowMillis: Long) {
        if (zone(nowMillis).noRespawn) out += playerId
    }

    /** Joined once respawns were over: out, watching, for the rest of the game. */
    private val sittingOut = HashSet<Int>()

    /** [playerId] joined after the start; once respawns are over that's too late to play. */
    fun onLateJoin(playerId: Int, nowMillis: Long) {
        if (!zone(nowMillis).noRespawn) return
        out += playerId
        sittingOut += playerId
    }

    /** The game at [nowMillis], for [players] (id to team) in it. */
    fun tick(nowMillis: Long, players: Map<Int, Int>): ModeState {
        val zone = zone(nowMillis)
        val left = players.filterKeys { it !in out }.values.groupingBy { it }.eachCount()
        teamsSeen = maxOf(teamsSeen, players.values.toSet().size)
        // The last team standing, once there were two to start with
        if (winner == 0 && teamsSeen >= 2 && left.size == 1) winner = left.keys.single()
        return ModeState(
            mode = GameMode.BATTLE_ROYALE, objectives = emptyList(), scores = left, scoreToWin = 0, radiusMeters = 0.0,
            winner = winner, movesAt = 0L, zone = zone, out = out.toSet(), sittingOut = sittingOut.toSet(),
        )
    }
}

/**
 * Marksman (map design: Marksman): each team's score is the total distance of its
 * eliminations, in whole metres, counting only those at least [MarksmanRules.minimumMeters].
 */
class MarksmanEngine(private val rules: MarksmanRules) {
    private var winner = 0

    fun tick(shots: List<com.simplecoil.simplecoil.domain.loadout.Shot>, teamOf: (Int) -> Int): ModeState {
        val scores = shots.filter { it.meters >= rules.minimumMeters }
            .groupBy { teamOf(it.shooterId) }
            .mapValues { (_, mine) -> mine.sumOf { it.meters }.toInt() }
        if (winner == 0 && rules.distanceToWinMeters > 0) {
            scores.filterValues { it >= rules.distanceToWinMeters }.maxByOrNull { it.value }?.let { winner = it.key }
        }
        return ModeState(
            mode = GameMode.MARKSMAN, objectives = emptyList(), scores = scores, scoreToWin = rules.distanceToWinMeters.toInt(),
            radiusMeters = 0.0, winner = winner, movesAt = 0L,
        )
    }
}
