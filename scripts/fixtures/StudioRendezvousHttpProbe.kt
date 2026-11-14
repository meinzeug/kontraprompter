package de.kontrakurs.kontraprompter.pairing

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import de.kontrakurs.kontraprompter.network.AuthenticatedChannel
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.util.Base64
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Local HTTP fixture only. Never accepts a non-loopback origin or production credentials. */
private class FixtureHttp(private val base: URI, private val cookie: String) : AutoCloseable {
    private val lock = Any()
    private val active = mutableSetOf<HttpURLConnection>()
    private var closed = false
    init {
        require(base.scheme == "http" && base.host == "127.0.0.1" && base.port in 1..65535 && base.rawPath.isNullOrEmpty())
        require(cookie.length in 1..256 && cookie.all { it.code in 33..126 })
    }
    fun post(path: String, body: JsonObject): Pair<Int, JsonObject> {
        require(path.startsWith("/fixture/rooms"))
        val bytes = body.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= 24_576)
        val connection = base.resolve(path).toURL().openConnection() as HttpURLConnection
        synchronized(lock) { if (closed) throw IOException("Fixture closed"); active.add(connection) }
        try {
            connection.requestMethod = "POST"; connection.connectTimeout = 2_000; connection.readTimeout = 2_500
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Origin", "https://videomaker.dorfspy.de")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Cookie", cookie)
            connection.doOutput = true; connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }
            val code = connection.responseCode
            val input = if (code in 200..299) connection.inputStream else connection.errorStream ?: throw IOException("No error response")
            val result = input.use { stream ->
                val out = ByteArrayOutputStream(); val block = ByteArray(2048)
                while (true) {
                    val count = stream.read(block); if (count < 0) break
                    if (out.size() + count > 24_576) throw IOException("Fixture response too large")
                    out.write(block, 0, count)
                }
                JsonParser.parseString(out.toString(Charsets.UTF_8.name())).asJsonObject
            }
            return code to result
        } finally { synchronized(lock) { active.remove(connection) }; connection.disconnect() }
    }
    override fun close() {
        val connections = synchronized(lock) { closed = true; active.toList().also { active.clear() } }
        connections.forEach { it.disconnect() }
    }
}

private fun json(vararg fields: Pair<String, Any>): JsonObject = JsonObject().apply {
    fields.forEach { (key, value) -> when (value) {
        is String -> addProperty(key, value)
        is Long -> addProperty(key, value)
        else -> error("Unsupported fixture field")
    } }
}

private class FixtureWire(private val api: FixtureHttp, private val room: String, private val device: String, private val role: String) : StudioRelayWire {
    private fun call(op: String, vararg fields: Pair<String, Any>): Pair<Int, JsonObject> =
        api.post("/fixture/rooms/$room/$op", json("deviceId" to device, "role" to role, *fields))
    override fun send(sequence: Long, bytes: ByteArray): StudioRelayWire.SendResult {
        val (status, value) = call("send", "sequence" to sequence, "data" to Base64.getEncoder().encodeToString(bytes))
        if (status == 429 && value.get("error")?.asString == "backpressure") return StudioRelayWire.SendResult.BACKPRESSURE
        if (status != 200) throw IOException("Fixture send rejected")
        return StudioRelayWire.SendResult.ACCEPTED
    }
    override fun peek(): RelayByteEndpoint.Frame? {
        val (status, value) = call("peek")
        if (status != 200) throw IOException("Fixture receive rejected")
        if (value.get("empty")?.asBoolean == true) return null
        val sequence = value.get("sequence").asLong
        val text = value.get("data").asString
        val bytes = Base64.getDecoder().decode(text)
        if (Base64.getEncoder().encodeToString(bytes) != text) throw IOException("Invalid fixture frame")
        return RelayByteEndpoint.Frame(sequence, bytes)
    }
    override fun ack(sequence: Long) {
        if (call("ack", "sequence" to sequence).first != 200) throw IOException("Fixture ACK rejected")
    }
    override fun close() = api.close()
}

/** Synthetic sessions, transient rooms and test HMAC only; no Android app data. */
object StudioRendezvousHttpProbe {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 1)
        val fixtureCredentials = JsonParser.parseString(System.`in`.bufferedReader().readLine() ?: error("Missing fixture credentials")).asJsonObject
        val uri = URI(args[0]); val camera = FixtureHttp(uri, fixtureCredentials.get("cameraCookie").asString); val prompter = FixtureHttp(uri, fixtureCredentials.get("prompterCookie").asString)
        val cameraId = UUID.randomUUID().toString(); val prompterId = UUID.randomUUID().toString()
        val pool = Executors.newSingleThreadExecutor()
        val disconnected = CountDownLatch(1)
        var cameraSocket: StudioRelaySocket? = null; var prompterSocket: StudioRelaySocket? = null
        try {
            val createBody = json("deviceId" to cameraId, "attemptId" to UUID.randomUUID().toString())
            val created = camera.post("/fixture/rooms", createBody); check(created.first == 200) { "Fixture create HTTP ${created.first}" }
            val retried = camera.post("/fixture/rooms", createBody)
            check(retried.first == 200 && retried.second.get("roomId") == created.second.get("roomId") &&
                retried.second.get("joinCode") == created.second.get("joinCode") &&
                retried.second.get("expiresInMs").asLong <= created.second.get("expiresInMs").asLong) {
                "Create retry was not idempotent"
            }
            val room = created.second.get("roomId").asString; val code = created.second.get("joinCode").asString
            check(UUID.fromString(room).toString() == room)
            check(prompter.post("/fixture/rooms/join", json("roomId" to room, "deviceId" to prompterId, "joinCode" to code)).first == 200)
            val path = "/fixture/rooms/$room"
            fun member(device: String, role: String) = json("deviceId" to device, "role" to role)
            check(camera.post("$path/key", member(cameraId, "camera")).first == 403)
            check(camera.post("$path/approve", member(cameraId, "camera")).first == 200)
            check(prompter.post("$path/approve", member(prompterId, "prompter")).first == 200)
            val cameraKey = camera.post("$path/key", member(cameraId, "camera")); val prompterKey = prompter.post("$path/key", member(prompterId, "prompter"))
            check(cameraKey.first == 200 && prompterKey.first == 200)
            val token = cameraKey.second.get("token").asString; check(token == prompterKey.second.get("token").asString)
            cameraSocket = StudioRelaySocket.open(StudioRelayEndpoint(FixtureWire(camera, room, cameraId, "camera"))) { disconnected.countDown() }
            prompterSocket = StudioRelaySocket.open(StudioRelayEndpoint(FixtureWire(prompter, room, prompterId, "prompter"))) { disconnected.countDown() }
            cameraSocket.socket.soTimeout = 6_000; prompterSocket.socket.soTimeout = 6_000
            val camSocket = cameraSocket.socket
            val host = pool.submit<AuthenticatedChannel> { AuthenticatedChannel.host(camSocket, token, "camera") }
            val client = AuthenticatedChannel.client(prompterSocket.socket, token, "prompter")
            val server = host.get(6, TimeUnit.SECONDS)
            client.send("capture_query", "synthetic-test"); check(server.receive() == ("capture_query" to "synthetic-test"))
            server.send("sdp", "synthetic-sdp"); check(client.receive() == ("sdp" to "synthetic-sdp"))
            println("STUDIO_HTTP_HMAC_BIDIRECTIONAL_OK")
            val control = fixtureCredentials.get("controlSocket").asString
            require((control.startsWith("/tmp/kp-studio-relay-") || control.startsWith("/tmp/kp-studio-diag-")) && control.endsWith("/control.sock"))
            SocketChannel.open(StandardProtocolFamily.UNIX).use { socket ->
                socket.connect(UnixDomainSocketAddress.of(control))
                val command = ByteBuffer.wrap("{\"op\":\"revoke\",\"role\":\"camera\"}\n".toByteArray(Charsets.UTF_8))
                while (command.hasRemaining()) socket.write(command)
                val reply = ByteBuffer.allocate(512)
                check(socket.read(reply) > 0)
            }
            check(disconnected.await(6, TimeUnit.SECONDS)) { "Revocation did not close signaling" }
            println("STUDIO_HTTP_REVOKE_CLOSES_BRIDGE_OK")
            client.close(); server.close()
        } finally {
            cameraSocket?.close(); prompterSocket?.close(); camera.close(); prompter.close(); pool.shutdownNow()
        }
    }
}
