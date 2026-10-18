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

import com.simplecoil.simplecoil.domain.loadout.Shot
import com.simplecoil.simplecoil.domain.map.Border
import com.simplecoil.simplecoil.domain.map.FieldConfig
import com.simplecoil.simplecoil.domain.map.GeoMath
import com.simplecoil.simplecoil.domain.map.LatLon
import com.simplecoil.simplecoil.domain.session.SessionSound
import com.simplecoil.simplecoil.domain.session.SessionState
import com.simplecoil.simplecoil.domain.session.SpawnPhase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class BattleRoyaleTest {

    private val center = LatLon(39.0, -77.0)
    private val field = Border.Circle(center, 100.0)
    private val rules = BattleRoyaleRules(phases = 3, firstWaitSeconds = 60, shrinkSeconds = 30, pauseSeconds = 30, finalDiameterMeters = 20.0,
        respawnUntilPhase = 0)

    @Test
    fun eachCircleShrinksInsideTheLast() {
        val plan = ZonePlan(field, rules, 0, Random(1))
        assertEquals(4, plan.circles.size)
        assertEquals(10.0, plan.circles.last().radiusMeters, 0.01)
        for (i in 1 until plan.circles.size) {
            val (a, b) = plan.circles[i - 1] to plan.circles[i]
            assertTrue(b.radiusMeters < a.radiusMeters)
            // Wholly inside the last one
            assertTrue(GeoMath.distanceMeters(a.center, b.center) + b.radiusMeters <= a.radiusMeters + 0.5)
            assertTrue(field.contains(b.center))
        }
    }

    @Test
    fun theZoneWaitsShrinksAndPauses() {
        val plan = ZonePlan(field, rules, 0, Random(2))
        var zone = plan.at(10_000)
        assertEquals(0, zone.phase)
        assertFalse(zone.shrinking)
        assertEquals(plan.circles[1], zone.next)
        assertEquals(60_000L, zone.changesAt)

        // Half way through the first shrink
        zone = plan.at(75_000)
        assertEquals(1, zone.phase)
        assertTrue(zone.shrinking)
        val half = (plan.circles[0].radiusMeters + plan.circles[1].radiusMeters) / 2
        assertEquals(half, zone.current.radiusMeters, 0.5)

        // The pause after it
        zone = plan.at(100_000)
        assertFalse(zone.shrinking)
        assertEquals(plan.circles[1], zone.current)
        assertEquals(plan.circles[2], zone.next)
        assertEquals(120_000L, zone.changesAt)

        // After the last
        zone = plan.at(1_000_000)
        assertEquals(3, zone.phase)
        assertEquals(plan.circles[3], zone.current)
        assertNull(zone.next)
        assertEquals(0L, zone.changesAt)
    }

    @Test
    fun damageRisesEachPhase() {
        val plan = ZonePlan(field, rules.copy(damagePerTick = 2), 0, Random(3))
        assertEquals(2, plan.at(0).damage)
        assertEquals(2, plan.at(70_000).damage)
        assertEquals(4, plan.at(130_000).damage)
        assertEquals(2, ZonePlan(field, rules.copy(damagePerTick = 2, damageRises = false), 0, Random(3)).at(200_000).damage)
    }

    @Test
    fun respawnsEndAtTheChosenPhase() {
        val plan = ZonePlan(field, rules.copy(respawnUntilPhase = 2), 0, Random(4))
        assertFalse(plan.at(0).noRespawn)
        assertFalse(plan.at(70_000).noRespawn)
        assertTrue(plan.at(130_000).noRespawn)
        assertTrue(ZonePlan(field, rules, 0, Random(4)).at(0).noRespawn)
    }

    @Test
    fun theLastTeamStandingWins() {
        val engine = BattleRoyaleEngine(ZonePlan(field, rules.copy(respawnUntilPhase = 1), 0, Random(5)))
        val players = mapOf(1 to 1, 2 to 1, 3 to 2)
        // Before respawns end, an elimination doesn't count
        engine.onEliminated(3, 10_000)
        assertEquals(mapOf(1 to 2, 2 to 1), engine.tick(10_000, players).scores)

        engine.onEliminated(1, 70_000)
        var state = engine.tick(70_000, players)
        assertEquals(setOf(1), state.out)
        assertEquals(0, state.winner)
        engine.onEliminated(3, 80_000)
        state = engine.tick(80_000, players)
        assertEquals(1, state.winner)
    }

    @Test
    fun theRefereeCanCloseTheZoneNow() {
        val engine = BattleRoyaleEngine(ZonePlan(field, rules, 0, Random(7)))
        // Waiting for the first shrink at 60 s; closed at 10 s instead
        assertEquals(60_000L, engine.zone(10_000).changesAt)
        assertTrue(engine.closeNow(10_000))
        val zone = engine.zone(10_000)
        assertTrue(zone.shrinking)
        // The phones see the real clock: done shrinking 30 s from now
        assertEquals(40_000L, zone.changesAt)
        // Not while it is already closing
        assertFalse(engine.closeNow(20_000))
    }

    @Test
    fun marksmanScoresTheDistance() {
        val engine = MarksmanEngine(MarksmanRules(minimumMeters = 10.0, distanceToWinMeters = 100.0))
        val shots = listOf(Shot(1, 2, center, center, 40.0), Shot(1, 2, center, center, 5.0), Shot(2, 1, center, center, 30.0))
        var state = engine.tick(shots, { it })
        assertEquals(mapOf(1 to 40, 2 to 30), state.scores)
        assertEquals(0, state.winner)
        state = engine.tick(shots + Shot(1, 2, center, center, 70.0), { it })
        assertEquals(1, state.winner)
    }

    @Test
    fun theZoneGoesOverTheWireAndTheRulesKeep() {
        val state = BattleRoyaleEngine(ZonePlan(field, rules, 0, Random(6))).tick(75_000, mapOf(1 to 1, 2 to 2))
        assertEquals(state, ModeState.fromJson(JSONObject(state.toJson().toString())))
        val config = FieldConfig(mode = GameMode.BATTLE_ROYALE, battleRoyale = rules, marksman = MarksmanRules(12.0, 300.0))
        val back = FieldConfig.fromJson(config.toJson())
        assertEquals(GameMode.BATTLE_ROYALE, back.mode)
        assertEquals(rules, back.battleRoyale)
        assertEquals(MarksmanRules(12.0, 300.0), back.marksman)
    }

    @Test
    fun outsideTheZoneHurts() = runTest {
        val zone = ZoneState(com.simplecoil.simplecoil.domain.map.GeoCircle(center, 20.0), null, 1, 3, false, 0L, 2, 3, true)
        val mode = MutableStateFlow<ModeState?>(ModeState(GameMode.BATTLE_ROYALE, emptyList(), emptyMap(), 0, 0.0, 0, 0L, zone))
        val session = MutableStateFlow(SessionState(health = 10, maxHealth = 10, shotMode = 0, grenadePaired = false, spawn = SpawnPhase.None))
        val damage = mutableListOf<Int>()
        var noRespawn = false
        val controller = ZoneController(mode, session, object : ZoneController.Penalties {
            override fun damage(amount: Int) { damage += amount }
            override fun setNoRespawn(on: Boolean) { noRespawn = on }
            override fun revive() {}
        }, inGame = { true }, feedback = object : ZoneController.Feedback {
            override fun play(sound: SessionSound) {}
            override fun vibrate() {}
        }, scope = backgroundScope, clock = { testScheduler.currentTime })
        controller.onFix(GeoMath.offset(center, 50.0, 0.0))
        advanceTimeBy(100)
        assertTrue(noRespawn)
        assertEquals(30.0, controller.outside.value!!.metersToSafety, 0.5)
        assertTrue(damage.isEmpty())
        advanceTimeBy(3_100)
        assertEquals(listOf(2), damage)

        // Back inside: safe
        controller.onFix(center)
        advanceTimeBy(600)
        assertNull(controller.outside.value)
        advanceTimeBy(5_000)
        assertEquals(listOf(2), damage)
    }
}
