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
import com.simplecoil.simplecoil.domain.map.Spawn
import com.simplecoil.simplecoil.domain.session.SessionSound
import com.simplecoil.simplecoil.domain.session.SessionState
import com.simplecoil.simplecoil.domain.session.SpawnPhase
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LoadoutControllerTest {

    private val center = LatLon(39.0, -77.0)
    private fun at(northMeters: Double) = GeoMath.offset(center, northMeters, 0.0)

    private class FakeBlaster(val session: MutableStateFlow<SessionState>) : LoadoutController.Blaster {
        val calls = mutableListOf<String>()
        override fun setAmmoReserve(magazines: Int?) {
            calls += "reserve:$magazines"
            session.value = session.value.copy(reserve = magazines)
        }
        override fun grantShotMode(shotMode: Int) { calls += "shotMode:$shotMode" }
        override fun healFull() { calls += "heal" }
        override fun addOvershield(hits: Int) { calls += "shield:$hits" }
        override fun addExtraLife() { calls += "life" }
    }

    private class FakeServer(private val center: LatLon) : LoadoutController.Server {
        var now = 0L
        val claims = mutableListOf<String>()
        var grant: DropKind? = null
        var longest: LongestShot? = null
        override fun now() = now
        override fun claim(dropId: String, playerId: Int): DropKind? { claims += "$dropId/$playerId"; return grant }
        override fun uav(playerId: Int) = listOf(UavPlayer(9, 2, center), UavPlayer(8, 1, center))
        override fun longestShot() = longest
        var refreshes = 0
        override fun refresh() { refreshes++ }
    }

    private inner class Rig(scope: TestScope, rules: LoadoutRules, drops: List<Drop> = emptyList()) {
        val field = MutableStateFlow<FieldConfig?>(FieldConfig(loadout = rules, drops = drops, spawns = listOf(Spawn(1, center), Spawn(2, at(200.0)))))
        val session = MutableStateFlow(SessionState(health = 10, maxHealth = 10, shotMode = 0, grenadePaired = false))
        val blaster = FakeBlaster(session)
        val server = FakeServer(center)
        val kills = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
        var playing = false
        var inSpawn = false
        val sounds = mutableListOf<SessionSound>()
        val controller = LoadoutController(
            field, session, blaster, server, me = { 5 to 1 }, inGame = { playing }, inOwnSpawn = { inSpawn },
            feedback = object : LoadoutController.Feedback {
                override fun play(sound: SessionSound) { sounds += sound }
                override fun vibrate() {}
            },
            kills = kills, scope = scope.backgroundScope, io = StandardTestDispatcher(scope.testScheduler),
            clock = { scope.testScheduler.currentTime },
        )

        fun start() {
            playing = true
            controller.update()
        }
    }

    @Test
    fun limitedAmmoStartsFullAndRefillsOnRespawn() = runTest {
        val rig = Rig(this, LoadoutRules(ammo = AmmoMode.LIMITED, startingReserve = 4))
        runCurrent()
        rig.start()
        assertEquals(4, rig.session.value.reserve)

        rig.blaster.setAmmoReserve(1)
        rig.session.value = rig.session.value.copy(spawn = SpawnPhase.Spawning(5, "Bob", false))
        runCurrent()
        rig.session.value = rig.session.value.copy(spawn = SpawnPhase.None)
        runCurrent()
        assertEquals(4, rig.session.value.reserve)

        // The game ends: back to unlimited
        rig.playing = false
        rig.controller.update()
        assertNull(rig.session.value.reserve)
    }

    @Test
    fun yourSpawnRefillsTheReserve() = runTest {
        val rig = Rig(this, LoadoutRules(ammo = AmmoMode.LIMITED, startingReserve = 4))
        runCurrent()
        rig.start()
        rig.blaster.setAmmoReserve(2)
        runCurrent()
        rig.inSpawn = true
        rig.controller.update()
        assertEquals(4, rig.session.value.reserve)
        assertTrue(SessionSound.RELOAD in rig.sounds)
    }

    @Test
    fun unlimitedAmmoLeavesTheReserveAlone() = runTest {
        val rig = Rig(this, LoadoutRules())
        runCurrent()
        rig.start()
        assertNull(rig.session.value.reserve)
    }

    @Test
    fun standingOnADropClaimsIt() = runTest {
        val drop = Drop("d1", DropKind.SUPPLY, center, 0, 600_000)
        val rig = Rig(this, LoadoutRules(dropsEnabled = true, claimSeconds = 3), listOf(drop))
        rig.server.grant = DropKind.SUPPLY
        runCurrent()
        rig.start()
        val notices = mutableListOf<LoadoutController.Notice>()
        backgroundScope.launch { rig.controller.notices.collect { notices += it } }
        runCurrent()

        rig.controller.onFix(at(2.0))
        assertEquals(3, rig.controller.claiming.value!!.secondsLeft)
        advanceTimeBy(3_100)
        runCurrent()
        assertEquals(listOf("d1/5"), rig.server.claims)
        assertTrue("shotMode:${GameSettingsRepository.SHOT_MODE_FULL_AUTO}" in rig.blaster.calls)
        assertTrue(LoadoutController.Notice.Claimed(DropKind.SUPPLY) in notices)
        assertNull(rig.controller.claiming.value)
        // Off this map at once, and the field is fetched again for everyone else's news
        assertTrue(rig.controller.visibleDrops().isEmpty())
        assertEquals(1, rig.server.refreshes)
    }

    @Test
    fun steppingOffStartsTheClaimOver() = runTest {
        val drop = Drop("d1", DropKind.MEDKIT, center, 0, 600_000)
        val rig = Rig(this, LoadoutRules(dropsEnabled = true, claimSeconds = 5), listOf(drop))
        runCurrent()
        rig.start()
        rig.controller.onFix(at(1.0))
        advanceTimeBy(3_000)
        rig.controller.onFix(at(50.0))
        assertNull(rig.controller.claiming.value)
        rig.controller.onFix(at(1.0))
        assertEquals(5, rig.controller.claiming.value!!.secondsLeft)
        assertTrue(rig.server.claims.isEmpty())
    }

    @Test
    fun aDropNotYetLandedCannotBeClaimed() = runTest {
        val drop = Drop("d1", DropKind.MEDKIT, center, 60_000, 600_000)
        val rig = Rig(this, LoadoutRules(dropsEnabled = true), listOf(drop))
        runCurrent()
        rig.start()
        rig.controller.onFix(center)
        assertNull(rig.controller.claiming.value)
    }

    @Test
    fun nearestTeamOnlyHidesFarDrops() = runTest {
        val near = Drop("near", DropKind.AMMO, at(10.0), 0, 1)
        val far = Drop("far", DropKind.AMMO, at(190.0), 0, 1)
        val rig = Rig(this, LoadoutRules(dropsEnabled = true, dropsNearestTeamOnly = true), listOf(near, far))
        assertEquals(listOf(near), rig.controller.visibleDrops())
    }

    @Test
    fun killstreaksGiveAUavThenFullAuto() = runTest {
        val rig = Rig(this, LoadoutRules(killstreaks = true, uavKills = 2, fullAutoKills = 3, uavSeconds = 10))
        runCurrent()
        rig.start()
        repeat(2) { rig.kills.emit(Unit) }
        runCurrent()
        // The UAV shows the other team only
        assertEquals(listOf(9), rig.controller.uav.value.map { it.playerId })
        rig.kills.emit(Unit)
        runCurrent()
        assertTrue("shotMode:${GameSettingsRepository.SHOT_MODE_FULL_AUTO}" in rig.blaster.calls)
        advanceTimeBy(12_000)
        runCurrent()
        assertTrue(rig.controller.uav.value.isEmpty())
    }

    @Test
    fun aUavKillstreak_playsTheKillstreakThenTheUav() = runTest {
        val rig = Rig(this, LoadoutRules(killstreaks = true, uavKills = 1, fullAutoKills = 0, uavSeconds = 10))
        runCurrent()
        rig.start()
        rig.kills.emit(Unit)
        runCurrent()
        assertTrue(rig.sounds.isEmpty())
        advanceTimeBy(LoadoutController.KILLSTREAK_SOUND_DELAY_MILLIS + 10)
        runCurrent()
        assertEquals(listOf(SessionSound.KILLSTREAK, SessionSound.UAV), rig.sounds)
        assertEquals(listOf(9), rig.controller.uav.value.map { it.playerId })
    }

    @Test
    fun theKillThatEndsTheGame_playsNoKillstreak() = runTest {
        val rig = Rig(this, LoadoutRules(killstreaks = true, uavKills = 0, fullAutoKills = 1))
        runCurrent()
        rig.start()
        rig.kills.emit(Unit)
        runCurrent()
        // The game ends right after
        rig.playing = false
        advanceTimeBy(LoadoutController.KILLSTREAK_SOUND_DELAY_MILLIS + 10)
        runCurrent()
        assertTrue(SessionSound.KILLSTREAK !in rig.sounds)
    }

    @Test
    fun dyingEndsTheStreak() = runTest {
        val rig = Rig(this, LoadoutRules(killstreaks = true, uavKills = 0, fullAutoKills = 2))
        runCurrent()
        rig.start()
        rig.kills.emit(Unit)
        runCurrent()
        rig.session.value = rig.session.value.copy(spawn = SpawnPhase.Spawning(5, "Bob", false))
        runCurrent()
        rig.session.value = rig.session.value.copy(spawn = SpawnPhase.None)
        rig.kills.emit(Unit)
        runCurrent()
        assertTrue(rig.blaster.calls.none { it.startsWith("shotMode:") })
    }

    @Test
    fun theLongestShotComesAfterTheGame() = runTest {
        val rig = Rig(this, LoadoutRules())
        val shot = LongestShot("Ann", "Bob", 80.0, center, at(80.0))
        rig.server.longest = shot
        runCurrent()
        rig.start()
        rig.playing = false
        rig.controller.update()
        advanceTimeBy(LoadoutController.STATS_DELAY_MILLIS + 100)
        runCurrent()
        assertEquals(shot, rig.controller.longestShot.value)

        // Cleared when the next game starts
        rig.start()
        assertNull(rig.controller.longestShot.value)
    }

}
