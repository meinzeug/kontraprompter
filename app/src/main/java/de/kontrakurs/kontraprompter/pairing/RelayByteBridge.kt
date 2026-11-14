package de.kontrakurs.kontraprompter.pairing

import java.io.Closeable
import java.io.IOException
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/** Opaque ordered relay records. Operations need bounded timeouts; close must terminate in-flight work. */
internal interface RelayByteEndpoint : Closeable {
    data class Frame(val sequence: Long, val bytes: ByteArray)
    fun send(sequence: Long, bytes: ByteArray)
    fun receive(): Frame?
    fun acknowledge(sequence: Long)
}

/**
 * Bridges a local TLS socket and opaque relay records; never inspects plaintext.
 * This is not a TLS implementation. A closed/broken bridge requires a fresh TLS
 * handshake; no TLS state or partially delivered record is resumed after crash.
 * EOF, an unknown send result, or an unknown ACK result closes both directions
 * immediately and reports failure. This is an abort, not a graceful drain: even
 * successfully queued bytes may be lost. Callers must require a separate peer
 * application acknowledgement and must fence the old relay epoch before retrying.
 */
internal class RelayByteBridge(private val socket: Socket, private val relay: RelayByteEndpoint, private val onFailure: () -> Unit = {}) : Closeable {
    private val closed = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private val failureReported = AtomicBoolean(false)
    private val outbound = Thread({ runOutbound() }, "relay-tls-outbound").apply { isDaemon = true }
    private val inbound = Thread({ runInbound() }, "relay-tls-inbound").apply { isDaemon = true }

    fun start() {
        check(!closed.get() && started.compareAndSet(false, true))
        try { outbound.start(); inbound.start() } catch (error: Exception) { close(); throw error }
    }

    private fun reportFailure() { if (!closed.get() && failureReported.compareAndSet(false, true)) onFailure() }

    private fun runOutbound() {
        try {
            val input = socket.getInputStream(); val buffer = ByteArray(MAX_FRAME); var sequence = 1L
            while (!closed.get()) {
                val count = input.read(buffer); if (count < 0) { reportFailure(); break }; if (count == 0) continue
                if (sequence > MAX_SEQUENCE) throw IOException("Relay sequence exhausted")
                relay.send(sequence, buffer.copyOf(count)); sequence++
            }
        } catch (_: Exception) { reportFailure() }
        finally { close() }
    }

    private fun runInbound() {
        try {
            val output = socket.getOutputStream(); var expected = 1L; var lastHash: ByteArray? = null
            while (!closed.get()) {
                val frame = relay.receive()
                if (frame == null) { Thread.sleep(25); continue }
                if (frame.bytes.isEmpty() || frame.bytes.size > MAX_FRAME || frame.sequence !in 1..MAX_SEQUENCE) throw IOException("Invalid relay frame")
                val hash = MessageDigest.getInstance("SHA-256").digest(frame.bytes)
                if (frame.sequence == expected) {
                    output.write(frame.bytes); output.flush(); expected++; lastHash = hash
                } else if (frame.sequence != expected - 1 || lastHash == null || !MessageDigest.isEqual(lastHash, hash)) throw IOException("Invalid relay sequence or replay")
                // A retransmitted frame is acknowledged but never written twice.
                relay.acknowledge(frame.sequence)
            }
        } catch (_: Exception) { reportFailure() }
        finally { close() }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { socket.close() }; runCatching { relay.close() }
        outbound.interrupt(); inbound.interrupt()
    }

    companion object { internal const val MAX_FRAME = 16 * 1024; private const val MAX_SEQUENCE = 9_007_199_254_740_990L }
}
