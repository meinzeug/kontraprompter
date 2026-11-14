package de.kontrakurs.kontraprompter.pairing

import de.kontrakurs.kontraprompter.network.PinnedPairingTls
import de.kontrakurs.kontraprompter.network.SinglePairingKeyManager
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.security.KeyStore
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.security.auth.x500.X500Principal

/** Synthetic loopback fixture only. Credentials and keys are created by the test runner. */
private class FixtureEndpoint(private val url: String, private val role: String, private val key: String) : RelayByteEndpoint {
    private fun request(method: String, operation: String, data: ByteArray? = null): Pair<Int, Pair<String?, ByteArray>> {
        val connection = URI("$url/$role/$operation").toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 2000
            connection.readTimeout = 2000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("X-Fixture-Key", key)
            if (data != null) { connection.doOutput = true; connection.outputStream.use { it.write(data) } }
            val status = connection.responseCode
            val bytes = if (status == 200) connection.inputStream.use { it.readBytes() } else byteArrayOf()
            return status to (connection.getHeaderField("X-Sequence") to bytes)
        } finally { connection.disconnect() }
    }
    override fun send(sequence: Long, bytes: ByteArray) { check(request("POST", "send/$sequence", bytes).first == 200) }
    override fun receive(): RelayByteEndpoint.Frame? {
        val (status, response) = request("GET", "peek")
        if (status == 204) return null
        check(status == 200)
        return RelayByteEndpoint.Frame(checkNotNull(response.first).toLong(), response.second)
    }
    override fun acknowledge(sequence: Long) { check(request("POST", "ack/$sequence", byteArrayOf()).first == 200) }
    override fun close() = Unit
}

private fun testAdapterSequence() {
    val listener = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    val peer = Socket("127.0.0.1", listener.localPort).apply { soTimeout = 2000 }
    val bridgeSocket = listener.accept()
    val frames = LinkedBlockingQueue<RelayByteEndpoint.Frame>()
    val acknowledgements = LinkedBlockingQueue<Long>()
    val errors = AtomicInteger(); val failed = CountDownLatch(1)
    val callbackSawClosed = AtomicReference(false)
    val endpoint = object : RelayByteEndpoint {
        override fun send(sequence: Long, bytes: ByteArray) = Unit
        override fun receive() = frames.poll(100, TimeUnit.MILLISECONDS)
        override fun acknowledge(sequence: Long) { acknowledgements.put(sequence) }
        override fun close() = Unit
    }
    val bridge = RelayByteBridge(bridgeSocket, endpoint) { callbackSawClosed.set(bridgeSocket.isClosed); errors.incrementAndGet(); failed.countDown() }
    try {
        bridge.start()
        check(runCatching { bridge.start() }.isFailure)
        frames.put(RelayByteEndpoint.Frame(1, byteArrayOf(10, 11)))
        check(acknowledgements.poll(2, TimeUnit.SECONDS) == 1L)
        frames.put(RelayByteEndpoint.Frame(1, byteArrayOf(10, 11)))
        check(acknowledgements.poll(2, TimeUnit.SECONDS) == 1L)
        frames.put(RelayByteEndpoint.Frame(2, byteArrayOf(12)))
        check(acknowledgements.poll(2, TimeUnit.SECONDS) == 2L)
        val result = ByteArray(3); DataInputStream(peer.inputStream).readFully(result)
        check(result.contentEquals(byteArrayOf(10, 11, 12)))
        frames.put(RelayByteEndpoint.Frame(4, byteArrayOf(13)))
        check(failed.await(2, TimeUnit.SECONDS))
        check(errors.get() == 1)
        check(callbackSawClosed.get())
    } finally { bridge.close(); peer.close(); listener.close() }

    fun failureCase(action: (LinkedBlockingQueue<RelayByteEndpoint.Frame>, Socket) -> Unit, failAck: Boolean = false) {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val remote = Socket("127.0.0.1", server.localPort)
        val frames = LinkedBlockingQueue<RelayByteEndpoint.Frame>()
        val acks = LinkedBlockingQueue<Long>()
        val failed = CountDownLatch(1)
        val endpoint = object : RelayByteEndpoint {
            override fun send(sequence: Long, bytes: ByteArray) = Unit
            override fun receive() = frames.poll(100, TimeUnit.MILLISECONDS)
            override fun acknowledge(sequence: Long) {
                if (failAck) throw java.io.IOException("simulated lost acknowledgement")
                acks.put(sequence)
            }
            override fun close() = Unit
        }
        val bridge = RelayByteBridge(server.accept(), endpoint) { failed.countDown() }
        try {
            bridge.start()
            if (!failAck) {
                frames.put(RelayByteEndpoint.Frame(1, byteArrayOf(42)))
                check(acks.poll(2, TimeUnit.SECONDS) == 1L)
            }
            action(frames, remote)
            check(failed.await(2, TimeUnit.SECONDS))
        } finally { bridge.close(); remote.close(); server.close() }
    }
    failureCase({ queue, _ -> queue.put(RelayByteEndpoint.Frame(1, byteArrayOf(43))) })
    failureCase({ _, remote -> remote.shutdownOutput() })
    failureCase({ queue, _ -> queue.put(RelayByteEndpoint.Frame(1, byteArrayOf(42))) }, failAck = true)
}

/** Run by the Videomaker fixture server; both peers use pre-existing, synthetic pins. */
fun main(args: Array<String>) {
    require(args.size == 6)
    testAdapterSequence()
    val (url, cameraKey, clientKey, cameraPath, clientPath) = args
    val wrongPin = args[5]
    require(wrongPin in setOf("none", "server", "client", "client-alias", "lost-send", "lost-ack"))
    require(url.startsWith("http://127.0.0.1:"))
    val password = "fixture-only".toCharArray()
    fun store(path: String) = KeyStore.getInstance("PKCS12").apply { FileInputStream(path).use { load(it, password) } }
    fun pin(store: KeyStore) = MessageDigest.getInstance("SHA-256").digest(store.getCertificate("device").publicKey.encoded)
    val camera = store(cameraPath); val client = store(clientPath)
    val manager = SinglePairingKeyManager(camera, password, "device")
    check(manager.getPrivateKey("device") != null && manager.getPrivateKey("distractor") == null)
    check(manager.getCertificateChain("device")?.size == 1 && manager.getCertificateChain("distractor") == null)
    check(manager.getServerAliases("EC", null)?.contentEquals(arrayOf("device")) == true)
    check(manager.getServerAliases("RSA", null) == null)
    check(manager.getServerAliases("EC", arrayOf(X500Principal("CN=unrelated"))) == null)
    check(runCatching { SinglePairingKeyManager(camera, password, "missing") }.isFailure)
    val cameraTls = PinnedPairingTls(camera, password, if (wrongPin == "client") ByteArray(32) else pin(client), "device")
    val clientTls = PinnedPairingTls(client, password, if (wrongPin == "server") ByteArray(32) else pin(camera), if (wrongPin == "client-alias") "distractor" else "device")
    val loopback = InetAddress.getByName("127.0.0.1")
    val cameraListener = cameraTls.listen(loopback, 0, 10000)
    val proxy = ServerSocket(0, 1, loopback).apply { soTimeout = 10000 }
    val errors = AtomicReference<Throwable?>()
    val bridgeFailed = CountDownLatch(1)
    val cameraBridge = RelayByteBridge(Socket(loopback, cameraListener.localPort), FixtureEndpoint(url, "owner", cameraKey)) { bridgeFailed.countDown() }
    val clientBridge = AtomicReference<RelayByteBridge?>()
    val cameraThread = Thread {
        try {
            cameraListener.accept().use { socket ->
                check(socket.session.protocol == "TLSv1.3")
                val input = DataInputStream(socket.inputStream); val output = DataOutputStream(socket.outputStream)
                check(input.readUTF() == "fixture-camera-secret-unique-486921")
                output.writeUTF("fixture-prompter-secret-unique-798351"); output.flush()
                check(input.readUTF() == "fixture-finished")
            }
        } catch (error: Throwable) { errors.set(error) }
    }.apply { isDaemon = true }
    val proxyThread = Thread {
        try { RelayByteBridge(proxy.accept(), FixtureEndpoint(url, "guest", clientKey)) { bridgeFailed.countDown() }.also { clientBridge.set(it); it.start() } }
        catch (error: Throwable) { errors.compareAndSet(null, error) }
    }.apply { isDaemon = true }
    try {
        cameraThread.start(); cameraBridge.start(); proxyThread.start()
        var connected = false
        try {
            clientTls.connect(loopback, proxy.localPort, 10000).use { socket ->
                connected = true
                check(socket.session.protocol == "TLSv1.3")
                val output = DataOutputStream(socket.outputStream); val input = DataInputStream(socket.inputStream)
                output.writeUTF("fixture-camera-secret-unique-486921"); output.flush()
                check(input.readUTF() == "fixture-prompter-secret-unique-798351")
                output.writeUTF("fixture-finished"); output.flush()
                cameraThread.join(5000)
                check(!cameraThread.isAlive)
                errors.get()?.let { throw it }
            }
        } catch (error: Exception) {
            if (wrongPin == "none") throw error
            if (wrongPin == "server") check(generateSequence(error as Throwable?) { it.cause }.any { it.message?.contains("Gegenstelle nicht gepinnt") == true })
            else if (wrongPin == "client" || wrongPin == "client-alias") {
                cameraThread.join(5000)
                check(generateSequence(errors.get()) { it.cause }.any { it.message?.contains("Gegenstelle nicht gepinnt") == true })
            } else check(bridgeFailed.await(5, TimeUnit.SECONDS))
        }
        check(connected == (wrongPin == "none"))
        println(if (connected) "PINNED_TLS_OK" else if (wrongPin.startsWith("lost-")) "LOST_RESPONSE_ABORTED" else "WRONG_PIN_REJECTED")
    } finally {
        clientBridge.get()?.close(); cameraBridge.close(); proxy.close(); cameraListener.close()
        cameraThread.join(2000); proxyThread.join(2000)
    }
}
