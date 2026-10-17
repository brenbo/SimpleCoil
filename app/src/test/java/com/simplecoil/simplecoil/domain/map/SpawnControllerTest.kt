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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SpawnControllerTest {

    private val center = LatLon(39.0, -77.0)
    private fun at(northMeters: Double, eastMeters: Double) = GeoMath.offset(center, northMeters, eastMeters)
    private val redSpawn = Spawn(1, at(0.0, -80.0))
    private val blueSpawn = Spawn(2, at(0.0, 80.0))

    private val field = MutableStateFlow<FieldConfig?>(FieldConfig(
        border = Border.Circle(center, 150.0),
        rules = BorderRules(accuracyGateMeters = 15.0),
        spawns = listOf(redSpawn, blueSpawn),
        spawnRules = SpawnRules(secondsInSpawn = 3, radiusMeters = 10.0, crossfadeSeconds = 10, oldSpawnGraceSeconds = 15),
    ))
    private val session = MutableStateFlow(SessionState(health = 10, maxHealth = 10, shotMode = 0, grenadePaired = false))
    private var team = 1
    private var inGame = true
    private val calls = mutableListOf<String>()
    private val scope = TestScope(UnconfinedTestDispatcher())

    private val controller = SpawnController(
        field = field,
        session = session,
        actions = object : SpawnController.Actions {
            override fun holdRespawn(held: Boolean) { calls += "held:$held" }
            override fun endShield() { calls += "endShield" }
        },
        myTeam = { team to false },
        inGame = { inGame },
        scope = scope,
        clock = { scope.testScheduler.currentTime },
    )

    private fun advance(millis: Long) {
        scope.testScheduler.advanceTimeBy(millis)
        scope.testScheduler.runCurrent()
    }

    private fun eliminated() {
        session.value = session.value.copy(spawn = SpawnPhase.Spawning(10, "", startingGame = false))
    }

    private fun lastHeld() = calls.lastOrNull { it.startsWith("held") }

    @Test
    fun eliminatedPlayersWalkBackToTheirSpawn() {
        controller.onFix(at(0.0, 0.0), 5f)
        eliminated()
        assertEquals("held:true", lastHeld())
        val guide = controller.guide.value!!
        assertEquals(redSpawn.point, guide.target)
        assertEquals(70.0, guide.meters, 1.0)

        // The enemy's spawn doesn't count
        controller.onFix(blueSpawn.point, 5f)
        advance(5_000)
        assertEquals("held:true", lastHeld())

        // Their own does, after a few seconds there
        controller.onFix(redSpawn.point, 5f)
        advance(2_000)
        assertEquals("held:true", lastHeld())
        advance(1_000)
        assertEquals("held:false", lastHeld())
        assertNull(controller.guide.value)
    }

    @Test
    fun leavingTheSpawnTooSoonStartsOver() {
        controller.onFix(redSpawn.point, 5f)
        eliminated()
        advance(2_000)
        controller.onFix(at(0.0, 0.0), 5f)
        controller.onFix(redSpawn.point, 5f)
        advance(2_000)
        assertEquals("held:true", lastHeld())
        advance(1_000)
        assertEquals("held:false", lastHeld())
    }

    @Test
    fun withoutAFixNothingIsHeld() {
        // No GPS: walking back can't be checked, so the player isn't trapped
        eliminated()
        assertTrue(calls.none { it == "held:true" })
    }

    @Test
    fun aStaleFixStopsHolding() {
        controller.onFix(at(0.0, 0.0), 5f)
        eliminated()
        assertEquals("held:true", lastHeld())
        advance(5 * 60_000L + 1_000)
        assertEquals("held:false", lastHeld())
    }

    @Test
    fun walkBackOffMeansRespawnAnywhere() {
        field.value = field.value!!.copy(spawnRules = field.value!!.spawnRules.copy(walkBack = false))
        controller.onFix(at(0.0, 0.0), 5f)
        eliminated()
        assertTrue(calls.none { it == "held:true" })
    }

    @Test
    fun theGameStartOnlyWaitsWhenAsked() {
        controller.onFix(at(0.0, 0.0), 5f)
        session.value = session.value.copy(spawn = SpawnPhase.Spawning(10, "", startingGame = true))
        assertTrue(calls.none { it == "held:true" })
        field.value = field.value!!.copy(spawnRules = field.value!!.spawnRules.copy(startAtSpawn = true))
        assertEquals("held:true", lastHeld())
    }

    @Test
    fun aMovedSpawnStillWorksForAWhile() {
        controller.onFix(at(0.0, 0.0), 5f)
        // The server moves red's spawn
        val newRed = Spawn(1, at(60.0, -60.0))
        field.value = field.value!!.copy(spawns = listOf(newRed, blueSpawn), spawnGeneration = 1)
        assertTrue(controller.isFading())
        eliminated()

        // The old one still counts within its grace time
        controller.onFix(redSpawn.point, 5f)
        advance(3_000)
        assertEquals("held:false", lastHeld())

        // But not once that has passed
        session.value = session.value.copy(spawn = SpawnPhase.None)
        advance(20_000)
        eliminated()
        controller.onFix(redSpawn.point, 5f)
        advance(5_000)
        assertEquals("held:true", lastHeld())
        assertEquals(newRed.point, controller.guide.value!!.target)
    }

    @Test
    fun movedSpawnsFade() {
        val newRed = Spawn(1, at(60.0, -60.0))
        field.value = field.value!!.copy(spawns = listOf(newRed, blueSpawn), spawnGeneration = 1)
        advance(5_000)
        val shown = controller.shown()
        assertEquals(0.5, shown.single { it.spawn == redSpawn }.opacity, 0.01)
        assertEquals(0.5, shown.single { it.spawn == newRed }.opacity, 0.01)
        assertTrue(shown.single { it.spawn == newRed }.mine)
        assertFalse(shown.single { it.spawn == blueSpawn }.mine)
        advance(6_000)
        assertFalse(controller.isFading())
        assertTrue(controller.shown().none { it.spawn == redSpawn })
    }

    @Test
    fun manualRespawnNeedsTheSpawnButNoWaitThere() {
        controller.onFix(at(0.0, 0.0), 5f)
        session.value = session.value.copy(spawn = SpawnPhase.AwaitingRespawn("", needsSpawn = false))
        assertEquals("held:true", lastHeld())
        controller.onFix(redSpawn.point, 5f)
        // Standing in it is enough: the player then holds reload
        assertEquals("held:false", lastHeld())
    }

    @Test
    fun aRoughButUsableFixStillCounts() {
        // Older phones often report 20-40 m; that still says which spawn someone is in
        controller.onFix(at(0.0, 0.0), 35f)
        eliminated()
        assertEquals("held:true", lastHeld())
    }

    @Test
    fun noFixIsReported() {
        eliminated()
        assertTrue(controller.noFix.value)
        controller.onFix(at(0.0, 0.0), 5f)
        assertFalse(controller.noFix.value)
    }

    @Test
    fun layersSplitAMove() {
        val newRed = Spawn(1, at(60.0, -60.0))
        field.value = field.value!!.copy(spawns = listOf(newRed, blueSpawn), spawnGeneration = 1)
        advance(2_500)
        val layers = controller.layers()!!
        assertEquals(listOf(blueSpawn), layers.steady)
        assertEquals(listOf(newRed), layers.incoming)
        assertEquals(listOf(redSpawn), layers.outgoing)
        assertEquals(0.25, layers.progress, 0.01)
        advance(10_000)
        assertEquals(listOf(newRed, blueSpawn), controller.layers()!!.steady)
    }

    @Test
    fun theShieldEndsOutsideTheSpawnWhenAsked() {
        field.value = field.value!!.copy(spawnRules = field.value!!.spawnRules.copy(shieldOnlyInSpawn = true))
        session.value = session.value.copy(shieldSeconds = 5)
        controller.onFix(redSpawn.point, 5f)
        assertTrue(calls.none { it == "endShield" })
        controller.onFix(at(0.0, -60.0), 5f)
        assertTrue(calls.contains("endShield"))
    }

    @Test
    fun theShieldIsLeftAloneByDefault() {
        session.value = session.value.copy(shieldSeconds = 5)
        controller.onFix(at(0.0, 0.0), 5f)
        assertTrue(calls.none { it == "endShield" })
    }

    @Test
    fun inaccurateFixesDoNotCount() {
        controller.onFix(at(0.0, 0.0), 5f)
        eliminated()
        controller.onFix(redSpawn.point, 60f)
        advance(5_000)
        assertEquals("held:true", lastHeld())
    }

    @Test
    fun theGameEndingLetsGo() {
        controller.onFix(at(0.0, 0.0), 5f)
        eliminated()
        inGame = false
        advance(1_000)
        assertEquals("held:false", lastHeld())
        assertNotNull(calls)
    }
}
