package de.kontrakurs.kontraprompter.video

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PairedStatusProbeTest {
    private val observation = PairedStatusObservation(1, "recording", "take", true, "project", false)

    @Test fun bindsReplyToExactChannelAndRequestAndConsumesOnce() {
        val gate = PairedStatusProbe(); val channel = Any()
        assertTrue(gate.begin(channel, "request", 100))
        assertFalse(gate.receive(Any(), "request", 101, observation))
        assertFalse(gate.receive(channel, "other", 101, observation))
        assertTrue(gate.receive(channel, "request", 101, observation))
        assertFalse(gate.receive(channel, "request", 102, observation))
        assertEquals(observation, gate.result(channel, 102))
        assertNull(gate.result(Any(), 102))
    }

    @Test fun rejectsExpiryRollbackAndOldRevisionAndAllowsLaterProbe() {
        val gate = PairedStatusProbe(); val channel = Any()
        assertTrue(gate.begin(channel, "first", 100))
        assertFalse(gate.receive(channel, "first", 99, observation))
        assertTrue(gate.begin(channel, "second", 200))
        assertFalse(gate.receive(channel, "second", 5200, observation))
        assertTrue(gate.begin(channel, "third", 5300))
        assertTrue(gate.receive(channel, "third", 5301, observation))
        assertNull(gate.result(channel, 10300))
        assertTrue(gate.begin(channel, "fourth", 10400))
        assertFalse(gate.receive(channel, "fourth", 10401, observation))
        assertTrue(gate.receive(channel, "fourth", 10402, observation.copy(revision = 2)))
    }

    @Test fun lateCancelCannotEraseNewRequest() {
        val gate = PairedStatusProbe(); val channel = Any()
        assertTrue(gate.begin(channel, "old", 0))
        assertTrue(gate.begin(channel, "new", 5000))
        gate.cancel(channel, "old")
        assertTrue(gate.receive(channel, "new", 5001, observation))
    }

    @Test fun disconnectRaceNeverLeavesOldObservationAfterReset() {
        repeat(100) {
            val gate = PairedStatusProbe(); val channel = Any(); val start = CountDownLatch(1)
            assertTrue(gate.begin(channel, "q", 1))
            val pool = Executors.newFixedThreadPool(2)
            val a = pool.submit { start.await(); gate.receive(channel, "q", 2, observation) }
            val b = pool.submit { start.await(); gate.reset() }
            start.countDown(); a.get(5, TimeUnit.SECONDS); b.get(5, TimeUnit.SECONDS)
            assertNull(gate.result(channel, 3)); pool.shutdownNow()
        }
    }
}
