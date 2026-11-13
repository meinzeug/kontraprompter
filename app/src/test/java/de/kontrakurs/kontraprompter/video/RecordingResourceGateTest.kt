package de.kontrakurs.kontraprompter.video

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class RecordingResourceGateTest {
    @Test fun `a second capture cannot start until the prior owner releases`() {
        val gate = RecordingResourceGate()
        val first = gate.tryAcquire()
        assertNotNull(first)
        assertNull(gate.tryAcquire())
        assertFalse(gate.isAvailable())
        gate.release(first!!)
        assertTrue(gate.isAvailable())
        val second = gate.tryAcquire()
        assertNotNull(second)
        gate.release(second!!)
    }
    @Test(expected = IllegalStateException::class)
    fun `stale owner cannot release a newer capture`() {
        val gate = RecordingResourceGate()
        val first = gate.tryAcquire()!!
        gate.release(first)
        gate.tryAcquire()
        gate.release(first)
    }

    @Test fun `late cleanup holds gate while worker remains blocked`() {
        val gate = RecordingResourceGate()
        val owner = gate.tryAcquire()!!
        val workerStarted = CountDownLatch(1)
        val workerCanExit = CountDownLatch(1)
        val cleanupDone = CountDownLatch(1)
        val worker = Thread { workerStarted.countDown(); workerCanExit.await() }.apply { start() }
        assertTrue(workerStarted.await(2, TimeUnit.SECONDS))
        val cleanup = Thread {
            awaitWorkersBeforeCleanup(listOf(worker), cleanup = { true }, released = { gate.release(owner); cleanupDone.countDown() })
        }.apply { start() }
        try {
            assertFalse(cleanupDone.await(100, TimeUnit.MILLISECONDS))
            assertNull(gate.tryAcquire())
        } finally {
            workerCanExit.countDown()
            worker.join(2000)
            cleanup.join(2000)
        }
        assertTrue(cleanupDone.await(2, TimeUnit.SECONDS))
        assertTrue(gate.isAvailable())
    }
}
