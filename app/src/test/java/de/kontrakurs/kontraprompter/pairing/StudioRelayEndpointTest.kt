package de.kontrakurs.kontraprompter.pairing

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class StudioRelayEndpointTest {
    private open class FakeWire : StudioRelayWire {
        override fun send(sequence: Long, bytes: ByteArray) = StudioRelayWire.SendResult.ACCEPTED
        override fun peek(): RelayByteEndpoint.Frame? = null
        override fun ack(sequence: Long) = Unit
        override fun close() = Unit
    }
    @Test fun explicitBackpressureRetriesIdenticalFrame() {
        val calls = mutableListOf<Pair<Long, List<Byte>>>()
        val wire = object : FakeWire() {
            override fun send(sequence: Long, bytes: ByteArray): StudioRelayWire.SendResult {
                calls.add(sequence to bytes.toList())
                return if (calls.size < 3) StudioRelayWire.SendResult.BACKPRESSURE else StudioRelayWire.SendResult.ACCEPTED
            }
        }
        val endpoint = StudioRelayEndpoint(wire, maxBackpressureMs = 500, delayMs = 1)
        endpoint.send(1, byteArrayOf(1, 2)); endpoint.close()
        assertEquals(3, calls.size); assertTrue(calls.all { it == (1L to listOf<Byte>(1, 2)) })
    }
    @Test fun ambiguousNetworkFailureIsNeverRetried() {
        val calls = AtomicInteger()
        val endpoint = StudioRelayEndpoint(object : FakeWire() {
            override fun send(sequence: Long, bytes: ByteArray): StudioRelayWire.SendResult { calls.incrementAndGet(); throw IOException("unknown") }
        })
        assertThrows(IOException::class.java) { endpoint.send(1, byteArrayOf(1)) }
        assertEquals(1, calls.get()); endpoint.close()
    }
    @Test fun closeInterruptsBackpressureWaitAndRejectsNewWork() {
        val entered = CountDownLatch(1)
        val endpoint = StudioRelayEndpoint(object : FakeWire() {
            override fun send(sequence: Long, bytes: ByteArray): StudioRelayWire.SendResult { entered.countDown(); return StudioRelayWire.SendResult.BACKPRESSURE }
        }, maxBackpressureMs = 10_000, delayMs = 1_000)
        val stopped = CountDownLatch(1)
        val thread = Thread { try { endpoint.send(1, byteArrayOf(1)) } catch (_: IOException) { } finally { stopped.countDown() } }
        thread.start(); assertTrue(entered.await(2, TimeUnit.SECONDS)); endpoint.close()
        assertTrue(stopped.await(2, TimeUnit.SECONDS)); assertThrows(IOException::class.java) { endpoint.receive() }
    }
    @Test fun rejectsOversizedAndInvalidFrames() {
        val endpoint = StudioRelayEndpoint(object : FakeWire() {
            override fun peek() = RelayByteEndpoint.Frame(0, byteArrayOf(1))
        })
        assertThrows(IOException::class.java) { endpoint.receive() }
        assertThrows(IllegalArgumentException::class.java) { endpoint.send(1, ByteArray(RelayByteBridge.MAX_FRAME + 1)) }
        endpoint.close()
    }
}
