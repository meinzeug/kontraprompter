package de.kontrakurs.kontraprompter.network

import org.junit.Assert.*
import org.junit.Test
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.security.KeyFactory
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

class PinnedPairingTlsTest {
    private val password = "local-fixture-only".toCharArray()
    private val loopback = InetAddress.getByName("127.0.0.1")

    private fun resource(name: String) = checkNotNull(javaClass.getResourceAsStream("/pinned-tls/$name"))
    private fun certificate(name: String): X509Certificate = resource("$name-cert.pem").use {
        CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
    }
    private fun privateKey(name: String): PrivateKey = resource("$name-key.pk8").use {
        KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(it.readBytes()))
    }
    private fun identity(name: String): KeyStore = KeyStore.getInstance("PKCS12").apply {
        load(null, null)
        setKeyEntry(name, privateKey(name), password, arrayOf(certificate(name)))
    }
    private fun pin(name: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(certificate(name).publicKey.encoded)
    private fun assertConnectionFails(connect: () -> SSLSocket) {
        val result = runCatching(connect)
        result.getOrNull()?.let { runCatching { it.close() } }
        assertTrue("connect() unexpectedly returned an authenticated socket", result.isFailure)
    }
    private fun rawContext(name: String, peer: String): SSLContext = SSLContext.getInstance("TLS").apply {
        val manager = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(identity(name), password) }
        init(manager.keyManagers, arrayOf(PinnedPeerTrust(pin(peer))), null)
    }
    private fun rawServer(): SSLServerSocket = (rawContext("host", "client").serverSocketFactory.createServerSocket(0, 1, loopback) as SSLServerSocket).apply {
        enabledProtocols = arrayOf("TLSv1.3"); needClientAuth = true; soTimeout = 5000
    }

    @Test fun `mutual pinned peers complete TLS before exchanging messages`() {
        val host = PinnedPairingTls(identity("host"), password, pin("client"))
        val client = PinnedPairingTls(identity("client"), password, pin("host"))
        val worker = Executors.newSingleThreadExecutor()
        try {
            host.listen(loopback, 0, 5000).use { listener ->
                val accepted = worker.submit<String> {
                    listener.accept().use { socket ->
                        assertEquals("TLSv1.3", socket.session.protocol)
                        assertEquals(0x41, socket.inputStream.read())
                        socket.outputStream.write(0x42); socket.outputStream.flush()
                        socket.session.peerPrincipal.name
                    }
                }
                client.connect(loopback, listener.localPort, 5000).use { socket ->
                    assertEquals("TLSv1.3", socket.session.protocol)
                    socket.outputStream.write(0x41); socket.outputStream.flush()
                    assertEquals(0x42, socket.inputStream.read())
                }
                assertTrue(accepted.get(5, TimeUnit.SECONDS).contains("synthetic-client-fixture-only"))
            }
        } finally { worker.shutdownNow() }
    }

    @Test fun `client rejects a different server key`() {
        val host = PinnedPairingTls(identity("host"), password, pin("client"))
        val client = PinnedPairingTls(identity("client"), password, pin("client"))
        val worker = Executors.newSingleThreadExecutor()
        try {
            host.listen(loopback, 0, 5000).use { listener ->
                val accepted = worker.submit<Boolean> { runCatching { listener.accept().use { it.inputStream.read() } }.isFailure }
                assertConnectionFails { client.connect(loopback, listener.localPort, 5000) }
                assertTrue(accepted.get(5, TimeUnit.SECONDS))
            }
        } finally { worker.shutdownNow() }
    }

    @Test fun `server rejects a different client key`() {
        val host = PinnedPairingTls(identity("host"), password, pin("host"))
        val client = PinnedPairingTls(identity("client"), password, pin("host"))
        val worker = Executors.newSingleThreadExecutor()
        try {
            host.listen(loopback, 0, 5000).use { listener ->
                val accepted = worker.submit<Boolean> { runCatching { listener.accept().use { it.outputStream.write(1) } }.isFailure }
                val clientResult = runCatching { client.connect(loopback, listener.localPort, 5000) }
                clientResult.getOrNull()?.let { runCatching { it.close() } }
                assertTrue(accepted.get(5, TimeUnit.SECONDS))
                assertTrue(clientResult.isFailure)
            }
        } finally { worker.shutdownNow() }
    }

    @Test fun `missing chain and modified pin never authenticate`() {
        val original = pin("host")
        val trust = PinnedPeerTrust(original)
        original.fill(0) // the caller cannot alter an already-configured pin
        trust.checkServerTrusted(arrayOf(certificate("host")), "EC")
        assertTrue(runCatching { trust.checkServerTrusted(null, "EC") }.exceptionOrNull() is CertificateException)
        assertTrue(runCatching { trust.checkServerTrusted(arrayOf(certificate("client")), "EC") }.exceptionOrNull() is CertificateException)
        assertTrue(runCatching { trust.checkClientTrusted(arrayOf(certificate("host")), "") }.exceptionOrNull() is CertificateException)
    }

    @Test fun `silent server times out and client closes the attempted connection`() {
        val client = PinnedPairingTls(identity("client"), password, pin("host"))
        val worker = Executors.newSingleThreadExecutor()
        try {
            ServerSocket(0, 1, loopback).use { listener ->
                val observedClose = worker.submit<Boolean> {
                    listener.accept().use { socket ->
                        socket.soTimeout = 3000
                        val buffer = ByteArray(4096)
                        while (socket.inputStream.read(buffer) != -1) { /* consume ClientHello, send nothing */ }
                        true
                    }
                }
                assertConnectionFails { client.connect(loopback, listener.localPort, 500) }
                assertTrue(observedClose.get(3, TimeUnit.SECONDS))
            }
        } finally { worker.shutdownNow() }
    }

    @Test fun `client rejects a server that completes TLS without confirming client authentication`() {
        val client = PinnedPairingTls(identity("client"), password, pin("host"))
        val worker = Executors.newSingleThreadExecutor()
        try {
            rawServer().use { server ->
                val received = worker.submit<Boolean> {
                    (server.accept() as SSLSocket).use { socket ->
                        socket.soTimeout = 5000; socket.startHandshake()
                        DataInputStream(socket.inputStream).readFully(ByteArray(36))
                        true // close without sending the protocol acknowledgement
                    }
                }
                assertConnectionFails { client.connect(loopback, server.localPort, 5000) }
                assertTrue(received.get(5, TimeUnit.SECONDS))
            }
        } finally { worker.shutdownNow() }
    }

    @Test fun `server rejects a client that does not confirm its challenge`() {
        val host = PinnedPairingTls(identity("host"), password, pin("client"))
        val worker = Executors.newSingleThreadExecutor()
        try {
            host.listen(loopback, 0, 5000).use { listener ->
                val accepted = worker.submit<Boolean> { runCatching { listener.accept().close() }.isFailure }
                (rawContext("client", "host").socketFactory.createSocket(loopback, listener.localPort) as SSLSocket).use { socket ->
                    socket.enabledProtocols = arrayOf("TLSv1.3"); socket.soTimeout = 5000; socket.startHandshake()
                    socket.outputStream.write(byteArrayOf(0x4b, 0x50, 0x52, 0x01) + ByteArray(32) { 1 }); socket.outputStream.flush()
                    DataInputStream(socket.inputStream).readFully(ByteArray(68))
                    socket.outputStream.write(byteArrayOf(0x4b, 0x50, 0x46, 0x01) + ByteArray(32)); socket.outputStream.flush()
                }
                assertTrue(accepted.get(5, TimeUnit.SECONDS))
            }
        } finally { worker.shutdownNow() }
    }

    @Test fun `closing listener cancels an accepted socket waiting for confirmation`() {
        val host = PinnedPairingTls(identity("host"), password, pin("client"))
        val worker = Executors.newSingleThreadExecutor()
        try {
            host.listen(loopback, 0, 5000).use { listener ->
                val accepted = worker.submit<Boolean> { runCatching { listener.accept().close() }.isFailure }
                (rawContext("client", "host").socketFactory.createSocket(loopback, listener.localPort) as SSLSocket).use { socket ->
                    socket.enabledProtocols = arrayOf("TLSv1.3"); socket.soTimeout = 5000; socket.startHandshake()
                    socket.outputStream.write(byteArrayOf(0x4b, 0x50, 0x52, 0x01) + ByteArray(32) { 1 }); socket.outputStream.flush()
                    DataInputStream(socket.inputStream).readFully(ByteArray(68)) // server is waiting for the final confirmation
                    listener.close()
                    assertTrue(accepted.get(5, TimeUnit.SECONDS))
                }
            }
        } finally { worker.shutdownNow() }
    }

    @Test fun `acknowledgement captured from one TLS session is rejected in another`() {
        val client = PinnedPairingTls(identity("client"), password, pin("host"))
        val worker = Executors.newSingleThreadExecutor()
        try {
            rawServer().use { server ->
                val finished = worker.submit<Boolean> {
                    var recorded = ByteArray(0)
                    repeat(2) { index ->
                        (server.accept() as SSLSocket).use { socket ->
                            socket.soTimeout = 5000; socket.startHandshake()
                            val request = ByteArray(36).also(DataInputStream(socket.inputStream)::readFully)
                            if (index == 0) recorded = byteArrayOf(0x4b, 0x50, 0x41, 0x01) + request.copyOfRange(4, 36) + ByteArray(32) { 2 }
                            socket.outputStream.write(recorded); socket.outputStream.flush()
                            if (index == 0) DataInputStream(socket.inputStream).readFully(ByteArray(36))
                        }
                    }
                    true
                }
                client.connect(loopback, server.localPort, 5000).close()
                assertConnectionFails { client.connect(loopback, server.localPort, 5000) }
                assertTrue(finished.get(5, TimeUnit.SECONDS))
            }
        } finally { worker.shutdownNow() }
    }
}
