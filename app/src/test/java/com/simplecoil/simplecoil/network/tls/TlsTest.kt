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

package com.simplecoil.simplecoil.network.tls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread

class TlsTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val loopback: InetAddress = InetAddress.getLoopbackAddress()

    /** A server under [identity] that echoes one line back, upper-cased; returns its port. */
    private fun echoServer(identity: ServerIdentity): ServerSocket {
        val server = identity.serverSocketFactory.createServerSocket(0, 1, loopback)
        thread(isDaemon = true) {
            try {
                server.accept().use { s ->
                    val line = s.getInputStream().bufferedReader().readLine()
                    s.getOutputStream().write((line.uppercase() + "\n").toByteArray())
                    s.getOutputStream().flush()
                }
            } catch (ignored: IOException) {
            }
        }
        return server
    }

    private fun connect(port: Int, pins: ServerPins): Socket {
        val plain = Socket()
        plain.connect(InetSocketAddress(loopback, port), 2_000)
        return TlsClient(pins).secure(plain, loopback, port)
    }

    @Test
    fun theServersOwnCertificate_isAccepted_andTheTrafficIsTls() {
        val identity = ServerIdentity.create()
        echoServer(identity).use { server ->
            val pins = ServerPins().apply { put(loopback.hostAddress!!, identity.fingerprint) }
            connect(server.localPort, pins).use { s ->
                assertTrue(s is SSLSocket)
                s.getOutputStream().write("where is red\n".toByteArray())
                s.getOutputStream().flush()
                assertEquals("WHERE IS RED", s.getInputStream().bufferedReader().readLine())
            }
        }
    }

    @Test
    fun anyOtherCertificate_isRefused() {
        val server = ServerIdentity.create()
        val impostor = ServerIdentity.create()
        echoServer(impostor).use { listening ->
            val pins = ServerPins().apply { put(loopback.hostAddress!!, server.fingerprint) }
            try {
                connect(listening.localPort, pins).close()
                fail("an impostor's certificate was accepted")
            } catch (expected: IOException) {
            }
        }
    }

    @Test
    fun noFingerprint_noConnection() {
        echoServer(ServerIdentity.create()).use { listening ->
            try {
                connect(listening.localPort, ServerPins()).close()
                fail("connected without a fingerprint")
            } catch (expected: IOException) {
            }
        }
    }

    @Test
    fun theIdentity_isKept_soTheFingerprintStaysTheSame() {
        val dir = folder.newFolder("tls")
        val first = ServerIdentity.loadOrCreate(dir)
        val again = ServerIdentity.loadOrCreate(dir)
        assertEquals(first.fingerprint, again.fingerprint)
        assertNotEquals(first.fingerprint, ServerIdentity.create().fingerprint)
        assertEquals(64, first.fingerprint.length)
    }
}
