package de.kontrakurs.kontraprompter.video

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test

class IdleCloseAdmissionTest {
    @Test fun busyRecorderNeverRunsCloseCallback() {
        var closed = false
        assertFalse(attemptIdleClose(Any(), busy = { true }) { closed = true })
        assertFalse(closed)
    }
    @Test fun recorderAdmissionWinsBeforeCloseChecksTheSameGuard() {
        val guard = Any()
        var busy = false
        var closed = false
        val admitted = CountDownLatch(1)
        val release = CountDownLatch(1)
        val worker = Thread {
            synchronized(guard) { busy = true; admitted.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        }
        worker.start()
        assertTrue(admitted.await(5, TimeUnit.SECONDS))
        val result = AtomicBoolean(true)
        val closer = Thread { result.set(attemptIdleClose(guard, busy = { busy }) { closed = true }) }
        closer.start()
        release.countDown()
        worker.join(5000); closer.join(5000)
        assertFalse(worker.isAlive); assertFalse(closer.isAlive)
        assertFalse(result.get()); assertFalse(closed)
    }
    @Test fun closeAdmissionWinsAndLaterStartSeesClosed() {
        val guard = Any()
        var closed = false
        assertTrue(attemptIdleClose(guard, busy = { false }) { closed = true })
        val startAdmitted = synchronized(guard) { !closed }
        assertFalse(startAdmitted)
    }
}
