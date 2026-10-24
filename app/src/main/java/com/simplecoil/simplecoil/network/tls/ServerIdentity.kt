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

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.net.ServerSocketFactory
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/**
 * This phone's identity as a dedicated server (v08): an EC P-256 key and a self-signed
 * certificate, made once and kept in the app's files. The game and field services listen with
 * TLS under it, and its [fingerprint] goes out in the discovery beacon, so the phones that join
 * accept this server's certificate and no other (TlsClient).
 */
class ServerIdentity private constructor(private val privateKey: PrivateKey, val certificate: X509Certificate) {

    /** The certificate's SHA-256, in hex: what phones pin. */
    val fingerprint: String = fingerprintOf(certificate)

    /** TLS server sockets under this identity. */
    val serverSocketFactory: ServerSocketFactory by lazy {
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setKeyEntry(ALIAS, privateKey, PASSWORD, arrayOf<Certificate>(certificate))
        }
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, PASSWORD) }
        SSLContext.getInstance("TLS").apply { init(keys.keyManagers, null, null) }.serverSocketFactory
    }

    companion object {
        private const val ALIAS = "server"
        private val PASSWORD = CharArray(0)
        private const val KEY_FILE = "server.key"
        private const val CERT_FILE = "server.crt"

        /** The identity kept in [dir], made (and kept) the first time. */
        @JvmStatic
        fun loadOrCreate(dir: File): ServerIdentity {
            val keyFile = File(dir, KEY_FILE)
            val certFile = File(dir, CERT_FILE)
            if (keyFile.exists() && certFile.exists()) {
                try {
                    return load(keyFile.readBytes(), certFile.readBytes())
                } catch (e: Exception) {
                    // Unreadable: make a new one (phones learn its fingerprint from the beacon)
                }
            }
            val made = create()
            dir.mkdirs()
            keyFile.writeBytes(made.privateKey.encoded)
            certFile.writeBytes(made.certificate.encoded)
            return made
        }

        /** A new identity, kept nowhere. */
        @JvmStatic
        fun create(random: SecureRandom = SecureRandom()): ServerIdentity {
            val keys = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1"), random) }.generateKeyPair()
            val der = SelfSignedCertificate.build(keys.public.encoded, keys.private, random)
            return ServerIdentity(keys.private, certificateOf(der))
        }

        private fun load(key: ByteArray, cert: ByteArray): ServerIdentity =
            ServerIdentity(KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(key)), certificateOf(cert))

        private fun certificateOf(der: ByteArray): X509Certificate =
            CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der)) as X509Certificate

        /** [certificate]'s SHA-256 in lowercase hex. */
        @JvmStatic
        fun fingerprintOf(certificate: Certificate): String =
            MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString("") { "%02x".format(it) }
    }
}

/**
 * A self-signed X.509 v3 certificate, written out in DER by hand: Java has no public API to
 * make one, and a library just for this would be heavy. Subject and issuer "CN=SimpleCoil
 * server", signed with ECDSA over SHA-256. Phones check it by its fingerprint only, so its
 * dates and names are only there because the format needs them.
 */
internal object SelfSignedCertificate {
    private val ECDSA_WITH_SHA256 = byteArrayOf(0x2A, 0x86.toByte(), 0x48, 0xCE.toByte(), 0x3D, 0x04, 0x03, 0x02)
    private val COMMON_NAME = byteArrayOf(0x55, 0x04, 0x03)
    private const val NAME = "SimpleCoil server"

    fun build(subjectPublicKeyInfo: ByteArray, signingKey: PrivateKey, random: SecureRandom): ByteArray {
        val algorithm = sequence(tlv(0x06, ECDSA_WITH_SHA256))
        val name = sequence(tlv(0x31, sequence(tlv(0x06, COMMON_NAME) + tlv(0x0C, NAME.toByteArray(Charsets.UTF_8)))))
        val serial = ByteArray(16).also { random.nextBytes(it); it[0] = (it[0].toInt() and 0x7F or 0x01).toByte() }
        val now = System.currentTimeMillis()
        val validity = sequence(utcTime(Date(now - DAY_MILLIS)) + utcTime(Date(now + 30 * 365 * DAY_MILLIS).coerceAtMost(LATEST_UTC_TIME)))
        val tbs = sequence(
            tlv(0xA0, tlv(0x02, byteArrayOf(2))) + // version 3
                tlv(0x02, BigInteger(1, serial).toByteArray()) +
                algorithm + name + validity + name +
                subjectPublicKeyInfo,
        )
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(signingKey)
            update(tbs)
            sign()
        }
        return sequence(tbs + algorithm + tlv(0x03, byteArrayOf(0) + signature))
    }

    private const val DAY_MILLIS = 24L * 60 * 60 * 1000

    /** UTCTime can't say 2050 or later. */
    private val LATEST_UTC_TIME = Date(2_524_607_999_000L) // 2049-12-31 23:59:59 UTC

    private fun Date.coerceAtMost(max: Date): Date = if (after(max)) max else this

    private fun utcTime(date: Date): ByteArray {
        val format = SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        return tlv(0x17, format.format(date).toByteArray(Charsets.US_ASCII))
    }

    private fun sequence(content: ByteArray) = tlv(0x30, content)

    /** A DER element: tag, length (short or long form), content. */
    private fun tlv(tag: Int, content: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag)
        val length = content.size
        when {
            length < 0x80 -> out.write(length)
            length < 0x100 -> { out.write(0x81); out.write(length) }
            length < 0x10000 -> { out.write(0x82); out.write(length shr 8); out.write(length and 0xFF) }
            else -> throw IOException("too long")
        }
        out.write(content)
        return out.toByteArray()
    }
}
