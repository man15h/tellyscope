// SPDX-License-Identifier: GPL-3.0-only
package app.tellyscope.adb

import com.flyfishxu.kadb.cert.KadbCert
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.security.KeyFactory
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class ConnectorTest {
    @BeforeTest fun key() = Connector.init(File(Files.createTempDirectory("adbkey").toFile(), "adbkey"))

    @Test fun fingerprintLooksLikeTheTvShowsIt() {
        // The TV prints 16 colon-separated hex pairs; the phone must show the same, and the same each time.
        val f = Connector.fingerprint()
        assertNotNull(f)
        assertTrue(Regex("(?:[0-9A-F]{2}:){15}[0-9A-F]{2}").matches(f), f)
        assertEquals(f, Connector.fingerprint())
    }

    @Test fun adbKeyLayoutIsWhatTheTvHashes() {
        val key = java.security.KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().public
            as java.security.interfaces.RSAPublicKey
        val b = java.nio.ByteBuffer.wrap(Connector.adbPublicKey(key)).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        assertEquals(524, b.capacity())
        assertEquals(64, b.getInt(0))
        // n0inv is -1/n mod 2^32, so n0inv * n's lowest word is all ones.
        assertEquals(-1, b.getInt(4) * b.getInt(8))
        assertEquals(65537, b.getInt(520))
    }

    @Test fun portOutOfRangeIsUnreachable() = runBlocking<Unit> {
        // InetSocketAddress threw IllegalArgumentException here, which nothing caught.
        assertIs<ConnectResult.Unreachable>(Connector.connect("127.0.0.1", 70000))
        assertIs<ConnectResult.Unreachable>(Connector.connect("127.0.0.1", 0))
    }

    @Test fun pairingThatDropsMidHandshakeIsUnreachable() = runBlocking<Unit> {
        // A pairing port that hangs up straight away, as one does when the Wi-Fi drops.
        ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")).use { server ->
            thread(isDaemon = true) { runCatching { while (true) server.accept().close() } }
            val r = withTimeout(30_000) { Connector.pair("127.0.0.1", server.localPort, "123456") }
            assertIs<PairResult.Unreachable>(r)
        }
    }

    @Test fun pairingWithNothingListeningIsUnreachable() = runBlocking<Unit> {
        val port = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        assertIs<PairResult.Unreachable>(withTimeout(30_000) { Connector.pair("127.0.0.1", port, "123456") })
    }

    @Test fun pairingThatGoesQuietIsUnreachableAfterTheTimeout() = runBlocking<Unit> {
        // The TV sleeps mid-pair: the socket stays open and nothing answers, and Kadb would wait forever.
        val held = CopyOnWriteArrayList<java.net.Socket>()
        ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")).use { server ->
            thread(isDaemon = true) { runCatching { while (true) held += server.accept() } }
            val r = withTimeout(10_000) { Connector.pair("127.0.0.1", server.localPort, "123456", timeout = 1.seconds) }
            assertEquals(PairResult.Unreachable("the TV stopped answering"), r)
        }
        held.forEach { it.close() } // lets the read Kadb is parked in go
    }

    @Test fun aPortThatGoesAwayMidHandshakeIsUnreachable() = runBlocking<Unit> {
        // The TV sleeps or leaves the Wi-Fi mid-handshake: the socket drops and the port stops answering.
        // That's a TV out of reach, not one that refused this phone.
        ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")).use { server ->
            thread(isDaemon = true) {
                runCatching {
                    server.accept().close() // the TCP probe
                    server.accept().use { s ->
                        java.io.DataInputStream(s.getInputStream()).readFully(ByteArray(24))
                        server.close()
                    }
                }
            }
            val r = withTimeout(30_000) { Connector.connect("127.0.0.1", server.localPort, allowWaitMs = 10_000) }
            assertIs<ConnectResult.Unreachable>(r)
        }
    }

    @Test fun aPortThatDropsTheHandshakeButStillAnswersIsNotAllowed() = runBlocking<Unit> {
        // Deny on the TV: adbd drops the socket and goes on listening.
        ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")).use { server ->
            thread(isDaemon = true) {
                while (true) runCatching { server.accept().use { s -> java.io.DataInputStream(s.getInputStream()).readFully(ByteArray(24)) } }
            }
            val r = withTimeout(30_000) { Connector.connect("127.0.0.1", server.localPort, allowWaitMs = 10_000) }
            assertEquals(ConnectResult.NotAllowed, r)
        }
    }

    @Test fun badCodeIsWrongCode() = runBlocking<Unit> {
        assertEquals(PairResult.WrongCode("pairing code must be 6 digits"), Connector.pair("127.0.0.1", 1, "12a456"))
    }

    @Test fun wirelessPortThatRefusesOurKeyNeedsPairing() = runBlocking<Unit> {
        // A wireless debugging connect port on a TV that hasn't paired with this phone: it asks for TLS,
        // lets TLS 1.3 finish on our side, then turns the key down. This read as "the TV didn't answer".
        ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")).use { server ->
            thread(isDaemon = true) { while (true) runCatching { server.accept().use(::refuseUnpairedKey) } }
            val r = withTimeout(30_000) { Connector.connect("127.0.0.1", server.localPort, allowWaitMs = 10_000) }
            assertEquals(ConnectResult.NeedsPairing, r)
        }
    }

    @Test fun wirelessPortThatDropsMidHandshakeStaysUnreachable() = runBlocking<Unit> {
        // A paired TV whose Wi-Fi blips: it asks for TLS, then the socket goes. The link must keep
        // reconnecting, so this can't read as "needs pairing".
        ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")).use { server ->
            thread(isDaemon = true) { while (true) runCatching { server.accept().use(::answerStls) } }
            val r = withTimeout(30_000) { Connector.connect("127.0.0.1", server.localPort, allowWaitMs = 10_000) }
            assertIs<ConnectResult.Unreachable>(r)
        }
    }

    /** adbd's side: read CNXN, answer STLS, then turn down every client certificate in TLS. */
    private fun refuseUnpairedKey(socket: java.net.Socket) {
        answerStls(socket)
        // The server borrows this phone's own identity; only the client check matters here.
        val id = KadbCert.ensureReady()
        val cert = CertificateFactory.getInstance("X.509").generateCertificate(id.certificatePem.inputStream())
        val der = Base64.getMimeDecoder().decode(String(id.privateKeyPem).replace(Regex("-----[A-Z ]+-----"), ""))
        val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(der))
        val store = KeyStore.getInstance("PKCS12").apply { load(null); setKeyEntry("adbd", key, CharArray(0), arrayOf(cert)) }
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, CharArray(0)) }
        val unknownKey = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
                throw CertificateException("not a paired key")
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val ctx = SSLContext.getInstance("TLSv1.3").apply { init(keys.keyManagers, arrayOf(unknownKey), null) }
        (ctx.socketFactory.createSocket(socket, null, socket.port, true) as SSLSocket).use { tls ->
            tls.useClientMode = false
            tls.needClientAuth = true
            tls.enabledProtocols = arrayOf("TLSv1.3")
            runCatching { tls.startHandshake() } // throws once it rejects ours
        }
    }

    /** Reads the CNXN hello and answers STLS, as a wireless debugging port does. */
    private fun answerStls(socket: java.net.Socket) {
        val input = java.io.DataInputStream(socket.getInputStream())
        val header = ByteArray(24).also(input::readFully)
        input.skipNBytes(ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).getInt(12).toLong())
        val stls = 0x534c5453
        socket.getOutputStream().write(
            ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(stls).putInt(0x01000000).putInt(0).putInt(0).putInt(0).putInt(stls.inv()).array(),
        )
    }
}
