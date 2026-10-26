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

import com.simplecoil.simplecoil.domain.loadout.DropKind
import com.simplecoil.simplecoil.domain.loadout.Fix
import com.simplecoil.simplecoil.domain.loadout.LoadoutRules
import com.simplecoil.simplecoil.domain.map.Border
import com.simplecoil.simplecoil.domain.map.FieldConfig
import com.simplecoil.simplecoil.domain.map.GeoMath
import com.simplecoil.simplecoil.domain.map.LatLon
import com.simplecoil.simplecoil.network.imagery.FieldConfigStore
import com.simplecoil.simplecoil.network.imagery.FieldImageryClient
import com.simplecoil.simplecoil.network.imagery.FieldImageryServer
import com.simplecoil.simplecoil.network.imagery.FieldPackStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetAddress

/** A game's drops going to phones and being claimed, over real loopback sockets. */
class LiveGameTransferTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val center = LatLon(39.0, -77.0)
    private val loopback: InetAddress = InetAddress.getLoopbackAddress()
    private val scope = TestScope(StandardTestDispatcher())
    private lateinit var server: FieldImageryServer
    private lateinit var game: LiveGame
    private val phase = MutableStateFlow(SessionPhase.LOBBY)

    @Before
    fun setUp() {
        val dir = File(tmp.root, "server")
        val field = FieldConfig(border = Border.Circle(center, 100.0),
            loadout = LoadoutRules(dropsEnabled = true, dropKinds = setOf(DropKind.MEDKIT), dropIntervalMinutes = 1, dropWarningSeconds = 0))
        val configs = FieldConfigStore(dir).apply { save(field) }
        server = FieldImageryServer(FieldPackStore(dir), configs, port = 0, clock = { 1_000L })
        server.start()
        game = LiveGame(server, savedField = { configs.refresh() }, positions = { emptyMap<Int, Fix>() },
            teamOf = { if (it < 10) 1 else 2 }, nameOf = { null }, wallClock = { 1_000L }, monoClock = { 0L })
        LiveGame.launchIn(scope, game, phase, emptyFlow())
        phase.value = SessionPhase.RUNNING
        scope.testScheduler.advanceTimeBy(60_001)
        scope.testScheduler.runCurrent()
    }

    @After
    fun tearDown() {
        server.stop()
        scope.cancel()
    }

    private fun phone(name: String): Pair<FieldConfigStore, FieldImageryClient> {
        val configs = FieldConfigStore(File(tmp.root, name))
        return configs to FieldImageryClient(FieldPackStore(File(tmp.root, name)), TestScope(), Dispatchers.IO, { 0L },
            port = server.localPort, configs = configs)
    }

    @Test
    fun onlyOnePlayerGetsADropAndItGoesForEveryone() {
        val (aConfig, a) = phone("a")
        val (bConfig, b) = phone("b")
        a.sync(loopback)
        b.sync(loopback)
        val drop = aConfig.config.value!!.drops.single()
        assertEquals(drop, bConfig.config.value!!.drops.single())

        assertEquals(DropKind.MEDKIT, a.claim(loopback, drop.id, 1))
        assertNull(b.claim(loopback, drop.id, 2))

        a.sync(loopback)
        b.sync(loopback)
        assertTrue(aConfig.config.value!!.drops.isEmpty())
        assertTrue(bConfig.config.value!!.drops.isEmpty())
    }

    @Test
    fun theRefereeDropsWhatTheyPick_whereTheyTap_onTheFieldOnly() {
        val (aConfig, a) = phone("a")
        a.sync(loopback)
        val before = aConfig.config.value!!.drops.size
        val spot = GeoMath.offset(center, 30.0, 0.0)
        assertEquals(LiveGame.Placed.OUTSIDE, game.dropAt(DropKind.UAV, GeoMath.offset(center, 300.0, 0.0)))
        assertEquals(LiveGame.Placed.DROPPED, game.dropAt(DropKind.UAV, spot))
        a.sync(loopback)
        val drops = aConfig.config.value!!.drops
        assertEquals(before + 1, drops.size)
        assertTrue(drops.any { it.kind == DropKind.UAV && it.point == spot })
    }
}

private fun TestScope.cancel() = coroutineContext[kotlinx.coroutines.Job]?.cancel()

class ServerGpsTest {
    @Test
    fun aPlayerIsReportingWithARecentFix() {
        val here = LatLon(39.0, -77.0)
        val report = ServerGps.report(listOf(1 to "Ann", 2 to "Bob", 3 to "Cat"),
            mapOf(1 to Fix(here, 95_000), 2 to Fix(here, 10_000)), 100_000)
        assertEquals(listOf(true, false, false), report.map { it.reporting })
    }
}

class LiveGameLateJoinTest {
    @Test
    fun someoneJoiningABattleRoyalePartWay_isListedToSitOut() {
        val scope = TestScope(StandardTestDispatcher())
        val center = LatLon(39.0, -77.0)
        val field = FieldConfig(border = Border.Circle(center, 100.0), mode = com.simplecoil.simplecoil.domain.mode.GameMode.BATTLE_ROYALE)
        val server = FieldImageryServer(FieldPackStore(java.io.File("unused")), port = 0)
        val players = mutableMapOf(1 to 1, 2 to 2)
        var now = 1_000_000L
        val phase = MutableStateFlow(SessionPhase.LOBBY)
        val game = LiveGame(server, savedField = { field }, positions = { emptyMap<Int, Fix>() },
            teamOf = { players[it] ?: 0 }, nameOf = { "p$it" }, players = { players.toMap() },
            wallClock = { now }, monoClock = { 0L }, startDelayMillis = { 5_000L })
        LiveGame.launchIn(scope, game, phase, emptyFlow())
        phase.value = SessionPhase.RUNNING
        scope.testScheduler.advanceTimeBy(1_001)
        // Well past the start countdown and the grace: player 3 joins
        now += 60_000
        players[3] = 2
        scope.testScheduler.advanceTimeBy(2_000)
        val mode = game.referee().mode!!
        assertEquals(setOf(3), mode.sittingOut)
        assertTrue(3 in mode.out)
        scope.cancel()
    }
}

class LiveGameWithoutGpsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun aClassicGameWithGpsOff_hasNoField_untilItEnds() {
        val scope = TestScope(StandardTestDispatcher())
        val center = LatLon(39.0, -77.0)
        val dir = File(tmp.root, "server")
        val configs = FieldConfigStore(dir).apply {
            save(FieldConfig(border = Border.Circle(center, 100.0), loadout = LoadoutRules(dropsEnabled = true)))
        }
        val server = FieldImageryServer(FieldPackStore(dir), configs, port = 0, clock = { 1_000L })
        server.start()
        var gps = false
        val phase = MutableStateFlow(SessionPhase.LOBBY)
        val game = LiveGame(server, savedField = { configs.refresh() }, positions = { emptyMap<Int, Fix>() },
            teamOf = { 1 }, nameOf = { null }, wallClock = { 1_000L }, monoClock = { 0L }, gpsOn = { gps })
        LiveGame.launchIn(scope, game, phase, emptyFlow())
        val phoneConfigs = FieldConfigStore(File(tmp.root, "phone"))
        val phone = FieldImageryClient(FieldPackStore(File(tmp.root, "phone")), TestScope(), Dispatchers.IO, { 0L },
            port = server.localPort, configs = phoneConfigs)
        val loopback = InetAddress.getLoopbackAddress()
        // In the lobby the phone gets the host's field (to see the map)
        phone.sync(loopback)
        assertTrue(phoneConfigs.config.value?.border != null)
        // A game with GPS off: no field, so nothing tracks positions
        phase.value = SessionPhase.RUNNING
        scope.testScheduler.runCurrent()
        phone.sync(loopback)
        assertNull(phoneConfigs.config.value)
        assertNull(game.referee().field)
        // Over: the field is back
        phase.value = SessionPhase.POST_GAME
        scope.testScheduler.runCurrent()
        phone.sync(loopback)
        assertTrue(phoneConfigs.config.value?.border != null)
        // With GPS on, a game keeps it
        gps = true
        phase.value = SessionPhase.RUNNING
        scope.testScheduler.runCurrent()
        phone.sync(loopback)
        assertTrue(phoneConfigs.config.value?.border != null)
        server.stop()
        scope.cancel()
    }
}

class LiveGameShotsTest {
    @Test
    fun shotsAreMeasuredOnAServerWithoutAField() {
        val scope = TestScope(StandardTestDispatcher())
        val here = LatLon(39.0, -77.0)
        val there = com.simplecoil.simplecoil.domain.map.GeoMath.offset(here, 50.0, 0.0)
        val server = FieldImageryServer(FieldPackStore(java.io.File("unused")), port = 0)
        val eliminations = kotlinx.coroutines.flow.MutableSharedFlow<com.simplecoil.simplecoil.network.ServerEvent.Eliminated>(extraBufferCapacity = 1)
        val phase = MutableStateFlow(SessionPhase.RUNNING)
        val game = LiveGame(server, savedField = { null }, positions = { mapOf(1 to Fix(here, 0), 2 to Fix(there, 0)) },
            teamOf = { it }, nameOf = { "p$it" }, monoClock = { 1_000L })
        LiveGame.launchIn(scope, game, phase, eliminations)
        scope.testScheduler.runCurrent()
        eliminations.tryEmit(com.simplecoil.simplecoil.network.ServerEvent.Eliminated(2, 1))
        scope.testScheduler.runCurrent()
        assertEquals(50.0, game.shots.longest()!!.meters, 1.0)
        scope.cancel()
    }
}
