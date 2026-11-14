package de.kontrakurs.kontraprompter.pairing

import de.kontrakurs.kontraprompter.network.PinnedPairingTls
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
import java.util.concurrent.atomic.AtomicReference

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

/** Run by the Videomaker fixture server; both peers use pre-existing, synthetic pins. */
fun main(args: Array<String>) {
    require(args.size == 6)
    val (url, cameraKey, clientKey, cameraPath, clientPath) = args
    val wrongPin = args[5]
    require(wrongPin in setOf("none", "server", "client"))
    require(url.startsWith("http://127.0.0.1:"))
    val password = "fixture-only".toCharArray()
    fun store(path: String) = KeyStore.getInstance("PKCS12").apply { FileInputStream(path).use { load(it, password) } }
    fun pin(store: KeyStore) = MessageDigest.getInstance("SHA-256").digest(store.getCertificate("device").publicKey.encoded)
    val camera = store(cameraPath); val client = store(clientPath)
    val cameraTls = PinnedPairingTls(camera, password, if (wrongPin == "client") ByteArray(32) else pin(client))
    val clientTls = PinnedPairingTls(client, password, if (wrongPin == "server") ByteArray(32) else pin(camera))
    val loopback = InetAddress.getByName("127.0.0.1")
    val cameraListener = cameraTls.listen(loopback, 0, 10000)
    val proxy = ServerSocket(0, 1, loopback).apply { soTimeout = 10000 }
    val errors = AtomicReference<Throwable?>()
    val cameraBridge = RelayByteBridge(Socket(loopback, cameraListener.localPort), FixtureEndpoint(url, "owner", cameraKey))
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
        try { RelayByteBridge(proxy.accept(), FixtureEndpoint(url, "guest", clientKey)).also { clientBridge.set(it); it.start() } }
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
            else {
                cameraThread.join(5000)
                check(generateSequence(errors.get()) { it.cause }.any { it.message?.contains("Gegenstelle nicht gepinnt") == true })
            }
        }
        check(connected == (wrongPin == "none"))
        println(if (connected) "PINNED_TLS_OK" else "WRONG_PIN_REJECTED")
    } finally {
        clientBridge.get()?.close(); cameraBridge.close(); proxy.close(); cameraListener.close()
        cameraThread.join(2000); proxyThread.join(2000)
    }
}
