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

package com.simplecoil.simplecoil.network.imagery

import com.simplecoil.simplecoil.domain.map.Border
import com.simplecoil.simplecoil.domain.map.FieldConfig
import com.simplecoil.simplecoil.domain.map.LatLon
import com.simplecoil.simplecoil.domain.map.OutOfBoundsPenalty
import com.simplecoil.simplecoil.domain.map.Spawn
import com.simplecoil.simplecoil.hardware.audio.AudioSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetAddress
import java.net.Socket

/** The server's field going to phones over real loopback sockets (map design: Field imagery packs). */
class FieldImageryTransferTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var serverStore: FieldPackStore
    private lateinit var server: FieldImageryServer
    private lateinit var serverConfig: FieldConfigStore
    private val loopback: InetAddress = InetAddress.getLoopbackAddress()

    @Before
    fun setUp() {
        serverStore = FieldPackStore(File(tmp.root, "server"))
        serverConfig = FieldConfigStore(File(tmp.root, "server"))
        server = FieldImageryServer(serverStore, serverConfig, port = 0)
        server.start()
    }

    @After
    fun tearDown() {
        server.stop()
    }

    /** The server counts a delivery on its own thread just after the phone has its reply: give it a moment. */
    private fun assertDelivered(expected: Int) {
        val deadline = System.currentTimeMillis() + 2_000
        while (server.deliveredCount.value != expected && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals(expected, server.deliveredCount.value)
    }

    private fun phone(name: String, clock: () -> Long = { 0L }): Pair<FieldPackStore, FieldImageryClient> {
        val store = FieldPackStore(File(tmp.root, name))
        return store to FieldImageryClient(store, TestScope(), Dispatchers.IO, clock, port = server.localPort,
            configs = FieldConfigStore(File(tmp.root, name)))
    }

    @Test
    fun aServerWithoutAFieldSaysSo() {
        val (store, client) = phone("phone")
        assertEquals(FieldImageryClient.Result.NONE, client.sync(loopback))
        assertNull(store.info.value)
        assertEquals(0, server.deliveredCount.value)
    }

    @Test
    fun aPhoneGetsTheFieldThenIsUpToDate() {
        val pack = FieldPacks.pack()
        serverStore.save(pack)
        val (store, client) = phone("phone")

        assertEquals(FieldImageryClient.Result.UPDATED, client.sync(loopback))
        assertEquals(pack.info, store.info.value)
        assertArrayEquals(pack.image, store.load()!!.image)
        assertDelivered(1)

        assertEquals(FieldImageryClient.Result.SAME, client.sync(loopback))
        assertDelivered(1)
    }

    @Test
    fun aNewFieldReplacesTheOld() {
        serverStore.save(FieldPacks.pack())
        val (store, client) = phone("phone")
        client.sync(loopback)

        val newer = FieldPacks.pack(image = FieldPacks.jpeg(size = 20_000, fill = 4))
        serverStore.save(newer)
        server.onFieldChanged()
        assertEquals(0, server.deliveredCount.value)

        assertEquals(FieldImageryClient.Result.UPDATED, client.sync(loopback))
        assertEquals(newer.info.id, store.info.value!!.id)
        assertDelivered(1)
    }

    @Test
    fun aPhoneDropsAFieldTheServerNoLongerHas() {
        serverStore.save(FieldPacks.pack())
        val (store, client) = phone("phone")
        client.sync(loopback)

        serverStore.clear()
        assertEquals(FieldImageryClient.Result.NONE, client.sync(loopback))
        assertNull(store.info.value)
    }

    @Test
    fun aBigPhotoArrivesWhole() {
        // About what a 1600 m field at 2048 pixels weighs
        val pack = FieldPacks.pack(image = FieldPacks.jpeg(size = 3 * 1024 * 1024, fill = 1))
        serverStore.save(pack)
        val (store, client) = phone("phone")
        assertEquals(FieldImageryClient.Result.UPDATED, client.sync(loopback))
        assertArrayEquals(pack.image, store.load()!!.image)
    }

    @Test
    fun manyPhonesAtOnce() {
        serverStore.save(FieldPacks.pack(image = FieldPacks.jpeg(size = 200_000)))
        val phones = (1..8).map { phone("phone$it") }
        val results = phones.map { (_, client) -> Thread { client.sync(loopback) }.apply { start() } }
        results.forEach { it.join(10_000) }
        for ((store, _) in phones) {
            assertEquals(serverStore.info.value, store.info.value)
        }
        // All from one address here, so they count as one phone
        assertDelivered(1)
    }

    @Test
    fun noServerIsAQuietFailure() {
        val (store, client) = phone("phone")
        server.stop()
        assertEquals(FieldImageryClient.Result.FAILED, client.sync(loopback))
        assertNull(store.info.value)
    }

    @Test
    fun garbageRequestsAreDropped() {
        serverStore.save(FieldPacks.pack())
        Socket(loopback, server.localPort).use { s ->
            s.getOutputStream().write("GET / HTTP/1.1\r\n\r\n".toByteArray())
            s.soTimeout = 5_000
            assertEquals(-1, s.getInputStream().read())
        }
        // Still serving afterwards
        assertEquals(FieldImageryClient.Result.UPDATED, phone("phone").second.sync(loopback))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun syncSoonDoesNotAskTooOften() {
        serverStore.save(FieldPacks.pack())
        var now = 0L
        val store = FieldPackStore(File(tmp.root, "phone"))
        val client = FieldImageryClient(store, TestScope(UnconfinedTestDispatcher()), UnconfinedTestDispatcher(), { now },
            port = server.localPort, minIntervalMillis = 30_000)

        client.syncSoon(loopback)
        assertEquals(serverStore.info.value, store.info.value)

        // A new field within the interval isn't fetched yet...
        val newer = FieldPacks.pack(image = FieldPacks.jpeg(fill = 2))
        serverStore.save(newer)
        now = 10_000
        client.syncSoon(loopback)
        assertEquals(FieldPacks.pack().info.id, store.info.value!!.id)

        // ...but is once the interval has passed
        now = 30_000
        client.syncSoon(loopback)
        assertEquals(newer.info.id, store.info.value!!.id)
    }

    private val border = FieldConfig("Park", Border.Circle(LatLon(39.0, -77.0), 120.0))

    @Test
    fun theBorderAndRulesGoToPhonesToo() {
        serverConfig.save(border)
        val store = FieldPackStore(File(tmp.root, "phone"))
        val configs = FieldConfigStore(File(tmp.root, "phone"))
        val client = FieldImageryClient(store, TestScope(), Dispatchers.IO, { 0L }, port = server.localPort, configs = configs)

        assertEquals(FieldImageryClient.Result.UPDATED, client.sync(loopback))
        assertEquals(border, configs.config.value)
        assertNull(store.info.value) // no photo yet
        assertDelivered(1)
        assertEquals(FieldImageryClient.Result.SAME, client.sync(loopback))

        // New rules reach the phone without the photo being sent again
        serverStore.save(FieldPacks.pack())
        client.sync(loopback)
        val stricter = border.copy(rules = border.rules.copy(penalty = OutOfBoundsPenalty.ELIMINATED))
        serverConfig.save(stricter)
        server.onFieldChanged()
        assertEquals(0, server.deliveredCount.value)
        assertEquals(FieldImageryClient.Result.UPDATED, client.sync(loopback))
        assertEquals(OutOfBoundsPenalty.ELIMINATED, configs.config.value!!.rules.penalty)
        assertEquals(serverStore.info.value, store.info.value)
        assertDelivered(1)

        // The host clears the border: the phone drops it
        serverConfig.clear()
        client.sync(loopback)
        assertNull(configs.config.value)
    }

    @Test
    fun movedSpawnsAreServedWithoutChangingTheSavedField() {
        val saved = border.copy(spawns = listOf(Spawn(1, LatLon(39.0, -77.0005)), Spawn(2, LatLon(39.0, -76.9995))))
        serverConfig.save(saved)
        val configs = FieldConfigStore(File(tmp.root, "phone"))
        val client = FieldImageryClient(FieldPackStore(File(tmp.root, "phone")), TestScope(), Dispatchers.IO, { 0L },
            port = server.localPort, configs = configs)
        client.sync(loopback)

        val moved = saved.copy(spawns = listOf(Spawn(1, LatLon(39.0003, -77.0)), Spawn(2, LatLon(38.9997, -77.0))), spawnGeneration = 1)
        server.liveConfig = moved
        assertEquals(FieldImageryClient.Result.UPDATED, client.sync(loopback))
        assertEquals(moved, configs.config.value)
        assertEquals(saved, serverConfig.refresh())

        // The game ends: back to the host's spawns
        server.liveConfig = null
        client.sync(loopback)
        assertEquals(saved, configs.config.value)
    }

    @Test
    fun presetsKeepAFieldByName() {
        val presets = FieldPresetStore(File(tmp.root, "presets"))
        assertEquals(emptyList<String>(), presets.names())
        val pack = FieldPacks.pack()
        presets.save("Johnson Park", pack, border)
        presets.save("back yard", null, border.copy(border = null))
        assertEquals(listOf("back yard", "Johnson Park"), presets.names())

        val (loadedPack, loadedConfig) = presets.load("Johnson Park")!!
        assertEquals(pack.info, loadedPack!!.info)
        assertEquals("Johnson Park", loadedConfig.name)
        assertEquals(border.border, loadedConfig.border)
        assertNull(presets.load("back yard")!!.first)

        // Saving under the same name replaces it
        presets.save("Johnson Park", null, border)
        assertNull(presets.load("Johnson Park")!!.first)

        presets.delete("back yard")
        assertEquals(listOf("Johnson Park"), presets.names())
        assertNull(presets.load("nowhere"))
    }

    @Test
    fun theProtocolLines() {
        assertEquals("SCFIELD1 GET -\n", FieldImageryProtocol.requestLine(null))
        assertEquals("SCFIELD1 GET abc\n", FieldImageryProtocol.requestLine("abc"))
        assertEquals(FieldImageryProtocol.Request(FieldImageryProtocol.Kind.PHOTO, ""), FieldImageryProtocol.parseRequest("SCFIELD1 GET -"))
        assertEquals(FieldImageryProtocol.Request(FieldImageryProtocol.Kind.CONFIG, "x1"), FieldImageryProtocol.parseRequest("SCFIELD1 CONFIG x1"))
        assertEquals("SCFIELD1 CONFIG -\n", FieldImageryProtocol.requestLine(null, FieldImageryProtocol.Kind.CONFIG))
        assertEquals("abc", FieldImageryProtocol.parseRequest("SCFIELD1 GET abc\r")?.knownId)
        assertNull(FieldImageryProtocol.parseRequest("SCFIELD1 PUT abc"))
        assertNull(FieldImageryProtocol.parseRequest("SCFIELD2 GET abc"))
        assertNull(FieldImageryProtocol.parseRequest("hello"))

        assertEquals(FieldImageryProtocol.Reply.None, FieldImageryProtocol.parseReply("SCFIELD1 NONE"))
        assertEquals(FieldImageryProtocol.Reply.Same, FieldImageryProtocol.parseReply("SCFIELD1 SAME"))
        assertEquals(FieldImageryProtocol.Reply.Pack(1234), FieldImageryProtocol.parseReply("SCFIELD1 PACK 1234"))
        assertNull(FieldImageryProtocol.parseReply("SCFIELD1 PACK -5"))
        assertNull(FieldImageryProtocol.parseReply("SCFIELD1 PACK ${Int.MAX_VALUE}"))
        assertNull(FieldImageryProtocol.parseReply("HTTP/1.1 200 OK"))
    }

    @Test
    fun aPlayerGetsTheSoundTheServerSetForThem_andOnlyThey() {
        val (_, client) = phone("phone")
        assertNull(client.sound(loopback, 3))

        val quiet = AudioSettings(effectsVolume = 0.25f, voiceVolume = 0.5f, voiceOn = true, essentialOnly = true, vibration = false)
        server.sounds.set(listOf(3), quiet)
        val first = client.sound(loopback, 3)!!
        assertEquals(quiet, first.settings)
        assertNull(client.sound(loopback, 4))

        // Set again: a newer revision, so the phone applies it even if it matches
        server.sounds.set(listOf(3, 4), quiet)
        assertTrue(client.sound(loopback, 3)!!.revision > first.revision)
        assertEquals(quiet, client.sound(loopback, 4)!!.settings)

        server.sounds.clear()
        assertNull(client.sound(loopback, 3))
    }
}
