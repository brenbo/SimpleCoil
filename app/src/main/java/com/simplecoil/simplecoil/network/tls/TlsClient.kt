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

import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

/** Turns a connected socket to a server into a secure one; a plain one in some tests. */
fun interface SecureConnector {
    /** [plain], connected to [server]:[port], as the socket to talk over. */
    @Throws(IOException::class)
    fun secure(plain: Socket, server: InetAddress, port: Int): Socket

    companion object {
        /** No TLS: for tests that read and write the frames themselves. */
        @JvmField
        val PLAIN = SecureConnector { plain, _, _ -> plain }
    }
}

/** The certificate fingerprint each server gave in its beacon, by address (v08). */
class ServerPins {
    private val pins = ConcurrentHashMap<String, String>()

    fun put(address: String, fingerprint: String) {
        pins[address.removePrefix("/")] = fingerprint.lowercase()
    }

    operator fun get(server: InetAddress): String? = server.hostAddress?.let { pins[it] }

    fun has(address: String): Boolean = pins.containsKey(address.removePrefix("/"))
}

/**
 * TLS to the servers this phone joins (v08): each server's certificate must be the one whose
 * fingerprint it gave in its discovery beacon ([pins]), or the connection fails. A server that
 * gave none is an older one, which this version can't play with.
 */
class TlsClient(private val pins: ServerPins) : SecureConnector {
    /** A context per fingerprint: TLS sessions resume within one, so the field service's many short requests stay cheap. */
    private val contexts = ConcurrentHashMap<String, SSLSocketFactory>()

    override fun secure(plain: Socket, server: InetAddress, port: Int): Socket {
        val pin = pins[server] ?: throw IOException("No certificate fingerprint for $server (an older server?)")
        val factory = contexts.getOrPut(pin) {
            SSLContext.getInstance("TLS").apply { init(null, arrayOf(PinnedTrustManager(pin)), null) }.socketFactory
        }
        val secure = factory.createSocket(plain, server.hostAddress, port, true) as SSLSocket
        val timeout = plain.soTimeout
        try {
            secure.soTimeout = HANDSHAKE_TIMEOUT_MS
            secure.startHandshake()
            secure.soTimeout = timeout
        } catch (e: IOException) {
            try {
                secure.close()
            } catch (ignored: IOException) {
            }
            throw e
        }
        return secure
    }

    companion object {
        const val HANDSHAKE_TIMEOUT_MS = 5_000
    }
}

/**
 * Accepts a server's certificate only if its SHA-256 is [fingerprint]: the server's own
 * self-signed certificate (ServerIdentity), named in its beacon. Anything else is refused.
 */
class PinnedTrustManager(private val fingerprint: String) : X509TrustManager {
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("No certificate")
        if (!ServerIdentity.fingerprintOf(leaf).equals(fingerprint, ignoreCase = true)) {
            throw CertificateException("Not the server's certificate")
        }
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        throw CertificateException("Phones don't present certificates")
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}
