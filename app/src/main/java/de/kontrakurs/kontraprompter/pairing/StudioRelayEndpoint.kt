package de.kontrakurs.kontraprompter.pairing

import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Transport-specific API. Each operation must authenticate both studio sessions
 * and the role-bound room. close() must interrupt outstanding network requests.
 * Implementations may report backpressure only for a definitely unaccepted frame.
 */
internal interface StudioRelayWire : AutoCloseable {
    enum class SendResult { ACCEPTED, BACKPRESSURE }
    fun send(sequence: Long, bytes: ByteArray): SendResult
    fun peek(): RelayByteEndpoint.Frame?
    fun ack(sequence: Long)
    override fun close()
}

/** Bounded adapter for RelayByteBridge; unknown network results always fail closed. */
internal class StudioRelayEndpoint(
    private val wire: StudioRelayWire,
    private val maxBackpressureMs: Long = 3_000,
    private val delayMs: Long = 25,
) : RelayByteEndpoint {
    private val closed = AtomicBoolean(false)
    private val wake = Object()
    init { require(maxBackpressureMs in 1..30_000 && delayMs in 1..1_000) }

    override fun send(sequence: Long, bytes: ByteArray) {
        checkOpen()
        require(sequence in 1..MAX_SEQUENCE && bytes.isNotEmpty() && bytes.size <= RelayByteBridge.MAX_FRAME)
        val fixed = bytes.copyOf()
        val start = System.nanoTime()
        val timeout = maxBackpressureMs * 1_000_000L
        while (true) {
            checkOpen()
            // Exceptions are deliberately not retried: delivery may be uncertain.
            when (wire.send(sequence, fixed)) {
                StudioRelayWire.SendResult.ACCEPTED -> { checkOpen(); return }
                StudioRelayWire.SendResult.BACKPRESSURE -> {
                    val remaining = timeout - (System.nanoTime() - start)
                    if (remaining <= 0) throw IOException("Relay ist dauerhaft ausgelastet")
                    val waitMs = minOf(delayMs, (remaining + 999_999L) / 1_000_000L).coerceAtLeast(1)
                    try { synchronized(wake) { if (!closed.get()) wake.wait(waitMs) } }
                    catch (_: InterruptedException) { Thread.currentThread().interrupt(); throw IOException("Relay wurde beendet") }
                }
            }
        }
    }
    override fun receive(): RelayByteEndpoint.Frame? {
        checkOpen()
        val frame = wire.peek() ?: return null
        checkOpen()
        if (frame.sequence !in 1..MAX_SEQUENCE || frame.bytes.isEmpty() || frame.bytes.size > RelayByteBridge.MAX_FRAME)
            throw IOException("Ungültiger Relay-Frame")
        return RelayByteEndpoint.Frame(frame.sequence, frame.bytes.copyOf())
    }
    override fun acknowledge(sequence: Long) { checkOpen(); require(sequence in 1..MAX_SEQUENCE); wire.ack(sequence); checkOpen() }
    override fun close() {
        if (closed.compareAndSet(false, true)) {
            synchronized(wake) { wake.notifyAll() }
            wire.close()
        }
    }
    private fun checkOpen() { if (closed.get()) throw IOException("Relay wurde beendet") }
    private companion object { const val MAX_SEQUENCE = 9_007_199_254_740_990L }
}
