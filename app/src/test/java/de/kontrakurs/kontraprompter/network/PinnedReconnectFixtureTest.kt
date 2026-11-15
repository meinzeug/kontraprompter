package de.kontrakurs.kontraprompter.network

import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.io.DataInputStream
import java.security.KeyFactory
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLContext
import javax.net.ssl.KeyManagerFactory

/** Synthetic certificates, real loopback TLS, no Android devices or app state. */
class PinnedReconnectFixtureTest {
    private val loopback = InetAddress.getByName("127.0.0.1")
    private val password = "local-fixture-only".toCharArray()
    private fun resource(name: String) = checkNotNull(javaClass.getResourceAsStream("/pinned-tls/$name"))
    private fun cert(name: String): X509Certificate = resource("$name-cert.pem").use {
        CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
    }
    private fun key(name: String): PrivateKey = resource("$name-key.pk8").use {
        KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(it.readBytes()))
    }
    private fun identity(name: String): KeyStore = KeyStore.getInstance("PKCS12").apply {
        load(null, null); setKeyEntry(name, key(name), password, arrayOf(cert(name)))
    }
    private fun pin(name: String) = MessageDigest.getInstance("SHA-256").digest(cert(name).publicKey.encoded)
    private fun tls(name: String, peer: String) = PinnedPairingTls(identity(name), password, pin(peer))

    @Test fun reconnectUsesFreshMutualTlsAfterDisconnect() {
        val workers = Executors.newSingleThreadExecutor()
        try {
            tls("host", "client").listen(loopback, 0, 5000).use { listener ->
                val server = workers.submit<Boolean> {
                    repeat(2) { i -> listener.accept().use { s ->
                        assertEquals(0x31 + i, s.inputStream.read())
                        s.outputStream.write(0x41 + i); s.outputStream.flush()
                    } }; true
                }
                PinnedReconnectFixture(tls("client", "host")).use { fixture ->
                    repeat(2) { i ->
                        val socket = checkNotNull(fixture.connect(loopback, listener.localPort, 5000))
                        assertEquals("TLSv1.3", socket.session.protocol)
                        socket.outputStream.write(0x31 + i); socket.outputStream.flush()
                        assertEquals(0x41 + i, socket.inputStream.read())
                        fixture.disconnect()
                        assertTrue(socket.isClosed)
                    }
                }
                assertTrue(server.get(5, TimeUnit.SECONDS))
            }
        } finally { workers.shutdownNow() }
    }

    @Test fun revokeWhileHandshakeCandidateWaitsClosesItAndBlocksLaterAttempts() {
        val workers = Executors.newFixedThreadPool(2)
        val ready = CountDownLatch(1); val release = CountDownLatch(1)
        try {
            tls("host", "client").listen(loopback, 0, 5000).use { listener ->
                val server = workers.submit<Boolean> { listener.accept().use { it.inputStream.read() == -1 } }
                val fixture = PinnedReconnectFixture(tls("client", "host"))
                try {
                    val attempt = workers.submit<SSLSocket?> { fixture.connect(loopback, listener.localPort, 5000) {
                        ready.countDown(); check(release.await(5, TimeUnit.SECONDS))
                    } }
                    assertTrue(ready.await(5, TimeUnit.SECONDS))
                    fixture.revoke(); release.countDown()
                    assertNull(attempt.get(5, TimeUnit.SECONDS))
                    assertNull(fixture.connect(loopback, listener.localPort, 5000))
                    assertTrue(server.get(5, TimeUnit.SECONDS))
                } finally { release.countDown(); fixture.close() }
            }
        } finally { workers.shutdownNow() }
    }

    @Test fun staleAttemptCannotReplaceANewerConnection() {
        val workers = Executors.newFixedThreadPool(3)
        val oldReady = CountDownLatch(1); val oldRelease = CountDownLatch(1)
        try {
            tls("host", "client").listen(loopback, 0, 5000).use { listener ->
                val server = workers.submit<Boolean> {
                    listener.accept().use { first -> listener.accept().use { second ->
                        first.inputStream.read() == -1 && second.inputStream.read() == -1
                    } }
                }
                val fixture = PinnedReconnectFixture(tls("client", "host"))
                try {
                    val old = workers.submit<SSLSocket?> { fixture.connect(loopback, listener.localPort, 5000) {
                        oldReady.countDown(); check(oldRelease.await(5, TimeUnit.SECONDS))
                    } }
                    assertTrue(oldReady.await(5, TimeUnit.SECONDS)); fixture.disconnect()
                    val current = checkNotNull(fixture.connect(loopback, listener.localPort, 5000))
                    oldRelease.countDown(); assertNull(old.get(5, TimeUnit.SECONDS))
                    assertFalse(current.isClosed); fixture.disconnect(); assertTrue(current.isClosed)
                    assertTrue(server.get(5, TimeUnit.SECONDS))
                } finally { oldRelease.countDown(); fixture.close() }
            }
        } finally { workers.shutdownNow() }
    }

    @Test fun wrongPinnedDeviceNeverProducesAnInstalledSocket() {
        val workers = Executors.newSingleThreadExecutor()
        try {
            tls("host", "client").listen(loopback, 0, 5000).use { listener ->
                val server = workers.submit<Boolean> { runCatching { listener.accept().close() }.isFailure }
                PinnedReconnectFixture(tls("client", "client")).use { fixture ->
                    assertTrue(runCatching { fixture.connect(loopback, listener.localPort, 5000) }.isFailure)
                }
                assertTrue(server.get(5, TimeUnit.SECONDS))
            }
        } finally { workers.shutdownNow() }
    }

    @Test fun replayedConfirmationCannotInstallTheNextConnection() {
        val worker = Executors.newSingleThreadExecutor()
        val raw = SSLContext.getInstance("TLS").apply {
            val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(identity("host"), password) }
            init(keys.keyManagers, arrayOf(PinnedPeerTrust(pin("client"))), null)
        }
        try {
            (raw.serverSocketFactory.createServerSocket(0, 1, loopback) as SSLServerSocket).use { server ->
                server.enabledProtocols = arrayOf("TLSv1.3"); server.needClientAuth = true; server.soTimeout = 5000
                val served = worker.submit<Boolean> {
                    var previous = ByteArray(0)
                    repeat(2) { index ->
                        (server.accept() as SSLSocket).use { socket ->
                            socket.soTimeout = 5000; socket.startHandshake()
                            val request = ByteArray(36).also(DataInputStream(socket.inputStream)::readFully)
                            if (index == 0) previous = byteArrayOf(0x4b, 0x50, 0x41, 0x01) + request.copyOfRange(4, 36) + ByteArray(32) { 2 }
                            socket.outputStream.write(previous); socket.outputStream.flush()
                            if (index == 0) DataInputStream(socket.inputStream).readFully(ByteArray(36))
                        }
                    }
                    true
                }
                PinnedReconnectFixture(tls("client", "host")).use { fixture ->
                    assertNotNull(fixture.connect(loopback, server.localPort, 5000)); fixture.disconnect()
                    assertTrue(runCatching { fixture.connect(loopback, server.localPort, 5000) }.isFailure)
                }
                assertTrue(served.get(5, TimeUnit.SECONDS))
            }
        } finally { worker.shutdownNow() }
    }

}
