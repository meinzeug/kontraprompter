package de.kontrakurs.kontraprompter.camera

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean

class CameraAdmissionFixtureTest {
    private fun command() = SyntheticAdmissionCommand("command", "pause", "take", "project")

    @Test fun `only actual admission receives accepted and execution needs matching status`() {
        val store = SyntheticAdmissionStore(); val f = CameraAdmissionFixture(store); val c = command()
        assertEquals(SyntheticAdmissionPhase.PREPARED, f.prepare(c)); assertNull(f.ack(c.id))
        assertEquals(SyntheticAdmissionPhase.ADMITTED, f.dispatch(c.id) { true }); assertEquals("accepted", f.ack(c.id))
        assertFalse(f.observed(c.id, c.recordingId, "wrong-project", true)); assertFalse(f.observed(c.id, c.recordingId, c.projectId, false))
        assertTrue(f.observed(c.id, c.recordingId, c.projectId, true)); assertEquals(SyntheticAdmissionPhase.EXECUTED, store.read(c.id)?.phase)
    }

    @Test fun `recorder mismatch is rejected and cannot pause another project`() {
        val store = SyntheticAdmissionStore(); val f = CameraAdmissionFixture(store); val c = command(); var actualProject = "other"
        f.prepare(c); val result = f.dispatch(c.id) { it.projectId == actualProject }
        assertEquals(SyntheticAdmissionPhase.REJECTED, result); assertEquals("rejected", f.ack(c.id))
        actualProject = c.projectId
        assertEquals(SyntheticAdmissionPhase.REJECTED, f.dispatch(c.id) { error("must not rerun") })
    }

    @Test fun `crash after effect before journal completion is uncertain and never reruns`() {
        val store = SyntheticAdmissionStore(); val f = CameraAdmissionFixture(store); val c = command(); var effects = 0
        f.prepare(c)
        assertThrows(IllegalStateException::class.java) { f.dispatch(c.id) { effects++; store.failBefore = true; true } }
        assertNull(f.ack(c.id)); store.failBefore = false
        val restarted = CameraAdmissionFixture(store); restarted.recover()
        assertEquals(SyntheticAdmissionPhase.UNCERTAIN, store.read(c.id)?.phase); assertNull(restarted.ack(c.id))
        assertEquals(SyntheticAdmissionPhase.UNCERTAIN, restarted.dispatch(c.id) { effects++; true }); assertEquals(1, effects)
    }

    @Test fun `uncertain committed admission is not reported accepted after restart`() {
        val store = SyntheticAdmissionStore(); val f = CameraAdmissionFixture(store); val c = command()
        f.prepare(c)
        assertThrows(IllegalStateException::class.java) { f.dispatch(c.id) { store.failAfter = true; true } }
        assertNull(f.ack(c.id)); store.failAfter = false
        val restarted = CameraAdmissionFixture(store); restarted.recover()
        assertEquals(SyntheticAdmissionPhase.UNCERTAIN, store.read(c.id)?.phase); assertNull(restarted.ack(c.id))
    }

    @Test fun `duplicate during blocked dispatch is not invoked and stop remains available`() {
        val store = SyntheticAdmissionStore(); val f = CameraAdmissionFixture(store); val c = command(); val count = AtomicInteger(); val stopped = AtomicBoolean()
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val executor = Executors.newSingleThreadExecutor()
        try {
            f.prepare(c)
            val pending = executor.submit<SyntheticAdmissionPhase> { f.dispatch(c.id) { count.incrementAndGet(); entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); !stopped.get() } }
            assertTrue(entered.await(5, TimeUnit.SECONDS)); assertEquals(SyntheticAdmissionPhase.DISPATCHING, f.dispatch(c.id) { count.incrementAndGet(); true })
            assertNull(f.ack(c.id))
            // A separate synthetic STOP changes recorder state before admission; it is not the real STOP implementation.
            if (c.recordingId == "take") stopped.set(true)
            assertTrue(stopped.get()); release.countDown(); assertEquals(SyntheticAdmissionPhase.REJECTED, pending.get(5, TimeUnit.SECONDS)); assertEquals(1, count.get())
        } finally { release.countDown(); executor.shutdownNow() }
    }

    @Test fun `restart refuses to replay prepared dispatching or admitted decisions`() {
        for (phase in listOf(SyntheticAdmissionPhase.PREPARED, SyntheticAdmissionPhase.DISPATCHING, SyntheticAdmissionPhase.ADMITTED)) {
            val c = command(); val store = SyntheticAdmissionStore(listOf(SyntheticAdmissionEntry(c, phase))); val f = CameraAdmissionFixture(store)
            f.recover(); assertEquals(SyntheticAdmissionPhase.UNCERTAIN, f.prepare(c)); assertNull(f.ack(c.id))
            assertEquals(SyntheticAdmissionPhase.UNCERTAIN, f.dispatch(c.id) { error("must not rerun") })
            assertThrows(IllegalArgumentException::class.java) { f.prepare(c.copy(projectId = "another")) }
        }
    }
}
