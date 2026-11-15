package de.kontrakurs.kontraprompter.network

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** A fresh 128-bit session secret, never transmitted on the wire. */
object PairingCrypto {
    private val random = SecureRandom()
    fun randomBytes(size: Int): ByteArray = ByteArray(size).also(random::nextBytes)
    fun token(): String = randomBytes(16).joinToString("") { "%02x".format(it.toInt() and 255) }
    fun parseToken(value: String): ByteArray {
        val normalized = value.filterNot { it == ' ' || it == '-' }.lowercase()
        require(normalized.matches(Regex("[0-9a-f]{32}"))) { "Der Sitzungscode muss aus 32 Hexadezimalzeichen bestehen." }
        return ByteArray(16) { normalized.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
    /** Length-prefixing prevents ambiguity when fields are concatenated. */
    fun mac(key: ByteArray, vararg fields: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        for (field in fields) {
            mac.update(byteArrayOf((field.size ushr 24).toByte(), (field.size ushr 16).toByte(), (field.size ushr 8).toByte(), field.size.toByte()))
            mac.update(field)
        }
        return mac.doFinal()
    }
    fun equal(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)
}

private const val MAX_PACKET = 1_048_576
private fun String.bytes() = toByteArray(Charsets.UTF_8)
private fun DataOutputStream.writeField(value: ByteArray) { require(value.size <= MAX_PACKET); writeInt(value.size); write(value) }
private fun DataInputStream.readField(max: Int = MAX_PACKET): ByteArray {
    val size = readInt()
    if (size < 0 || size > max) throw IOException("Ungültige Nachrichtenlänge")
    return ByteArray(size).also { readFully(it) }
}
private fun DataOutputStream.text(value: String) = writeField(value.bytes())
private fun DataInputStream.text(max: Int = MAX_PACKET) = readField(max).toString(Charsets.UTF_8)

/** Bidirectional proof of possession; each connection uses two fresh challenges. */
class AuthenticatedChannel private constructor(
    private val socket: Socket,
    private val input: DataInputStream,
    private val output: DataOutputStream,
    private val key: ByteArray,
    private val hostNonce: ByteArray,
    private val clientNonce: ByteArray,
    private val sendingRole: String,
    val remoteName: String,
) : AutoCloseable {
    // Captured on the established socket; never log or serialize these addresses.
    internal val localAddress = socket.localAddress
    internal val remoteAddress = socket.inetAddress
    private var outgoing = 0L
    private var incoming = 0L
    private val receivingRole = if (sendingRole == "host") "client" else "host"

    @Synchronized fun send(type: String, payload: String) {
        require(type.length in 1..32)
        require(payload.toByteArray(Charsets.UTF_8).size <= MAX_PACKET)
        val sequence = ++outgoing
        val signature = signature(key, hostNonce, clientNonce, sendingRole, sequence, type, payload)
        output.writeLong(sequence); output.text(type); output.text(payload); output.writeField(signature); output.flush()
    }
    fun receive(): Pair<String, String> {
        val sequence = input.readLong()
        val type = input.text(32)
        val payload = input.text()
        val signature = input.readField(32)
        if (sequence != incoming + 1 || !PairingCrypto.equal(signature, signature(key, hostNonce, clientNonce, receivingRole, sequence, type, payload))) throw IOException("Nachricht nicht authentisch oder erneut zugestellt")
        incoming = sequence
        return type to payload
    }
    override fun close() { socket.close() }

    companion object {
        private const val PROTOCOL = "KontraPrompter-1"
        private fun signature(key: ByteArray, host: ByteArray, client: ByteArray, role: String, seq: Long, type: String, payload: String) =
            PairingCrypto.mac(key, PROTOCOL.bytes(), host, client, role.bytes(), seq.toString().bytes(), type.bytes(), payload.bytes())
        private fun proof(key: ByteArray, host: ByteArray, client: ByteArray, hostName: String, clientName: String, role: String) =
            PairingCrypto.mac(key, PROTOCOL.bytes(), host, client, hostName.bytes(), clientName.bytes(), role.bytes())
        fun host(socket: Socket, token: String, hostName: String): AuthenticatedChannel {
            val key = PairingCrypto.parseToken(token)
            val input = DataInputStream(socket.getInputStream()); val output = DataOutputStream(socket.getOutputStream())
            val hostNonce = PairingCrypto.randomBytes(32)
            output.text(PROTOCOL); output.writeField(hostNonce); output.text(hostName); output.flush()
            val clientNonce = input.readField(32); if (clientNonce.size != 32) throw IOException("Ungültige Challenge")
            val clientName = input.text(128)
            val received = input.readField(32)
            if (!PairingCrypto.equal(received, proof(key, hostNonce, clientNonce, hostName, clientName, "client"))) throw IOException("Sitzungscode ungültig")
            output.writeField(proof(key, hostNonce, clientNonce, hostName, clientName, "host")); output.flush()
            return AuthenticatedChannel(socket, input, output, key, hostNonce, clientNonce, "host", clientName)
        }
        fun client(socket: Socket, token: String, clientName: String): AuthenticatedChannel {
            val key = PairingCrypto.parseToken(token)
            val input = DataInputStream(socket.getInputStream()); val output = DataOutputStream(socket.getOutputStream())
            if (input.text(32) != PROTOCOL) throw IOException("Unbekanntes Protokoll")
            val hostNonce = input.readField(32); if (hostNonce.size != 32) throw IOException("Ungültige Challenge")
            val hostName = input.text(128)
            val clientNonce = PairingCrypto.randomBytes(32)
            output.writeField(clientNonce); output.text(clientName); output.writeField(proof(key, hostNonce, clientNonce, hostName, clientName, "client")); output.flush()
            if (!PairingCrypto.equal(input.readField(32), proof(key, hostNonce, clientNonce, hostName, clientName, "host"))) throw IOException("Kameragerät konnte nicht authentifiziert werden")
            return AuthenticatedChannel(socket, input, output, key, hostNonce, clientNonce, "client", hostName)
        }
    }
}
