package de.kontrakurs.kontraprompter.network

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import javax.net.ssl.SSLSocket

private const val MAX_SIGNAL_PAYLOAD = 1_048_576
private const val IDLE_TIMEOUT_MS = 30_000
private const val FRAME_TIMEOUT_MS = 15_000L
private val SIGNAL_TYPE = Regex("^[a-z_]{1,32}$")

/** A total frame deadline, in addition to a separate idle/heartbeat timeout. */
internal class DeadlineInput(
    private val input: InputStream,
    private val setTimeoutMs: (Int) -> Unit,
    private val nowNs: () -> Long = System::nanoTime,
) : InputStream() {
    private var deadlineNs: Long? = null
    fun beginFrame() { deadlineNs = nowNs() + FRAME_TIMEOUT_MS * 1_000_000L }
    fun endFrame() { deadlineNs = null; setTimeoutMs(IDLE_TIMEOUT_MS) }
    private fun remainingMs(): Int {
        val deadline = deadlineNs ?: return IDLE_TIMEOUT_MS
        val left = deadline - nowNs()
        if (left <= 0) throw SocketTimeoutException("Signalisierungsnachricht dauerte zu lange")
        return ((left + 999_999L) / 1_000_000L).coerceAtMost(IDLE_TIMEOUT_MS.toLong()).toInt().coerceAtLeast(1)
    }
    override fun read(): Int {
        setTimeoutMs(remainingMs())
        val result = input.read()
        remainingMs() // A stream that returns after the deadline still fails closed.
        return result
    }
    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
        setTimeoutMs(remainingMs())
        val result = input.read(bytes, offset, length)
        remainingMs()
        return result
    }
}

/** Length-limited messages inside a freshly confirmed, mutually pinned TLS session. */
internal class SignalFrameReader(
    private val input: DataInputStream,
    private val beginFrame: () -> Unit = {},
    private val endFrame: () -> Unit = {},
) {
    private var sequence = 0L
    fun read(): Pair<String, String> {
        // The idle deadline applies before the first byte; a total frame deadline starts afterwards.
        val first = input.readUnsignedByte()
        beginFrame()
        try {
            var next = first.toLong()
            repeat(7) { next = (next shl 8) or input.readUnsignedByte().toLong() }
            if (sequence == Long.MAX_VALUE || next != sequence + 1) throw IOException("Ungültige Signalisierungsfolge")
            fun field(max: Int): String {
                val size = input.readInt()
                if (size < 0 || size > max) throw IOException("Ungültige Signalisierungslänge")
                val bytes = ByteArray(size).also(input::readFully)
                return try {
                    Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
                } catch (_: Exception) { throw IOException("Ungültige Signalisierungskodierung") }
            }
            val type = field(32)
            if (!SIGNAL_TYPE.matches(type)) throw IOException("Ungültiger Signalisierungstyp")
            val payload = field(MAX_SIGNAL_PAYLOAD)
            sequence = next
            return type to payload
        } finally { endFrame() }
    }
}

internal class SignalFrameWriter(private val output: DataOutputStream) {
    private var sequence = 0L
    fun write(type: String, payload: String) {
        require(SIGNAL_TYPE.matches(type))
        val name = type.toByteArray(Charsets.UTF_8)
        require(payload.length <= MAX_SIGNAL_PAYLOAD)
        val body = try {
            val encoded = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(payload))
            ByteArray(encoded.remaining()).also { encoded.get(it) }
        } catch (_: Exception) { throw IllegalArgumentException("Ungültige Signalisierungskodierung") }
        require(body.size <= MAX_SIGNAL_PAYLOAD && sequence < Long.MAX_VALUE)
        output.writeLong(++sequence)
        output.writeInt(name.size); output.write(name)
        output.writeInt(body.size); output.write(body)
        output.flush()
    }
}

/**
 * Can only be constructed through a PinnedPairingTls handshake. Fresh server
 * revocation and peer-journal checks are required before calling these methods.
 * The owning session must exchange heartbeats more often than the idle timeout.
 */
internal class PinnedTlsMessageChannel private constructor(private val socket: SSLSocket) : AutoCloseable {
    private val timed = DeadlineInput(socket.inputStream, { socket.soTimeout = it })
    private val reader = SignalFrameReader(DataInputStream(timed), timed::beginFrame, timed::endFrame)
    private val writer = SignalFrameWriter(DataOutputStream(socket.outputStream))
    private val readLock = Any()
    private val writeLock = Any()
    init { socket.soTimeout = IDLE_TIMEOUT_MS }

    fun send(type: String, payload: String) = synchronized(writeLock) {
        try { writer.write(type, payload) } catch (error: Exception) { close(); throw error }
    }
    fun receive(): Pair<String, String> = synchronized(readLock) {
        try { reader.read() } catch (error: Exception) { close(); throw error }
    }
    override fun close() { socket.close() }

    companion object {
        fun connect(tls: PinnedPairingTls, address: InetAddress, port: Int, timeoutMs: Int): PinnedTlsMessageChannel =
            PinnedTlsMessageChannel(tls.connect(address, port, timeoutMs))
        fun accept(listener: PinnedPairingTls.Listener): PinnedTlsMessageChannel =
            PinnedTlsMessageChannel(listener.accept())
    }
}
