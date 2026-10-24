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

package com.simplecoil.simplecoil

import com.simplecoil.simplecoil.data.repository.GameSettingsRepository
import com.simplecoil.simplecoil.data.repository.GameStateRepository
import com.simplecoil.simplecoil.data.repository.PlayerRepository
import com.simplecoil.simplecoil.domain.team.TeamMode
import com.simplecoil.simplecoil.network.EventBus
import com.simplecoil.simplecoil.network.WireProtocol
import com.simplecoil.simplecoil.network.server.GameServer
import com.simplecoil.simplecoil.network.tls.ServerIdentity
import com.simplecoil.simplecoil.network.tls.ServerPins
import com.simplecoil.simplecoil.network.tls.TlsClient
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.net.Socket

/** v08: the game server over TLS, as a phone joins it. */
class TlsGameLoopbackTest {
    private val settings = GameSettingsRepository()
    private val gameState = GameStateRepository()
    private val players = PlayerRepository(settings, gameState)
    private val identity = ServerIdentity.create()
    private lateinit var server: GameServer
    private val clients = ArrayList<LoopbackClient>()
    private val loopback = InetAddress.getByName("127.0.0.1")

    @Before
    fun setUp() {
        settings.teamMode = TeamMode.TWO_TEAMS
        LoopbackClient.awaitPortFree()
        server = GameServer(settings, players, gameState, EventBus(), clock = { System.nanoTime() / 1_000_000 },
            serverSockets = identity.serverSocketFactory)
        server.start()
        val until = System.currentTimeMillis() + 5000
        while (!server.isAccepting && System.currentTimeMillis() < until) Thread.sleep(10)
    }

    @After
    fun tearDown() {
        clients.forEach { it.close() }
        server.stop()
        server.shutdown()
        LoopbackClient.awaitPortFree()
    }

    private fun tlsClient(fingerprint: String) = LoopbackClient(open = { port ->
        val pins = ServerPins().apply { put("127.0.0.1", fingerprint) }
        TlsClient(pins).secure(Socket(loopback, port), loopback, port)
    }).also { clients += it }

    @Test
    fun aPhoneWithTheServersFingerprint_joinsOverTls() {
        val client = tlsClient(identity.fingerprint)
        client.hello(0, "Alpha", "AA:00")
        val reply = client.awaitMesg(WireProtocol.NETMSG_SERVERREPLY)
        assertTrue(reply.substring(WireProtocol.NETMSG_SERVERREPLY.length).toInt() in 1..64)
    }

    @Test
    fun aPhoneExpectingAnotherCertificate_cantConnect() {
        try {
            tlsClient(ServerIdentity.create().fingerprint)
            fail("connected to a server with another certificate")
        } catch (expected: java.io.IOException) {
        }
    }

    @Test
    fun anOlderPhoneSpeakingPlainText_isNeverAnswered() {
        val plain = LoopbackClient().also { clients += it }
        plain.hello(0, "Old", "BB:00")
        try {
            plain.awaitMesg(WireProtocol.NETMSG_SERVERREPLY, timeoutMs = 1500)
            fail("a plain-text phone was answered")
        } catch (expected: AssertionError) {
        }
    }
}
