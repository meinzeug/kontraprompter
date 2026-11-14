package de.kontrakurs.kontraprompter.cloud

import kotlinx.coroutines.Job
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class ManualUploadRaceGuardTest {
    private val id = "recording"

    @Test fun `pause during preflight prevents a late start from persisting`() {
        val guard = ManualUploadRaceGuard(); val token = guard.explicitStart(id)
        assertFalse(guard.pause(id) { false }) // no durable request yet
        var written = false
        assertFalse(guard.persistExplicit(token, id) { written = true })
        assertFalse(written)
    }

    @Test fun `pause after registration cancels the exact upload and blocks old revision`() {
        val guard = ManualUploadRaceGuard(); val token = guard.explicitStart(id); val job = Job()
        assertTrue(guard.persistExplicit(token, id) {})
        assertTrue(guard.register(token, id, job) { true })
        assertTrue(guard.pause(id) { true })
        assertTrue(job.isCancelled)
        assertFalse(guard.register(token, id, Job()) { true })
        guard.release(job)
    }

    @Test fun `failed pause blocks automatic retry until a new explicit durable action`() {
        val guard = ManualUploadRaceGuard(); val token = guard.explicitStart(id); val job = Job()
        assertTrue(guard.persistExplicit(token, id) {})
        assertTrue(guard.register(token, id, job) { true })
        try { guard.pause(id) { throw IOException("disk") }; fail("expected") } catch (_: IOException) { }
        assertTrue(job.isCancelled)
        guard.release(job)
        assertFalse(guard.register(guard.snapshot(), id, Job()) { true })
        val fresh = guard.explicitStart(id)
        assertTrue(guard.persistExplicit(fresh, id) {})
        assertTrue(guard.register(fresh, id, Job()) { true })
    }

    @Test fun `concurrent pause waits for durable start then prevents registration`() {
        val guard = ManualUploadRaceGuard(); val token = guard.explicitStart(id)
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val paused = AtomicBoolean(false)
        val failure = AtomicReference<Throwable?>(null)
        val starter = thread { try { guard.persistExplicit(token, id) { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) } } catch (e: Throwable) { failure.set(e) } }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val pauser = thread { paused.set(guard.pause(id) { true }) }
        release.countDown(); starter.join(5000); pauser.join(5000)
        assertFalse(starter.isAlive); assertFalse(pauser.isAlive); assertNull(failure.get()); assertTrue(paused.get())
        assertFalse(guard.register(token, id, Job()) { true })
    }

    @Test fun `stale job completion cannot release the current upload`() {
        val guard = ManualUploadRaceGuard(); val old = Job(); val current = Job()
        val token = guard.explicitStart(id); assertTrue(guard.persistExplicit(token, id) {})
        assertTrue(guard.register(token, id, current) { true })
        guard.release(old)
        assertFalse(guard.register(token, id, Job()) { true })
        guard.release(current)
        assertTrue(guard.register(token, id, Job()) { true })
    }
}
