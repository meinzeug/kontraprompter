package de.kontrakurs.kontraprompter.video

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class DirectPeerStopTest {
    @Test fun `queued close leaves recorder untouched and completes only once`() {
        val queue = mutableListOf<() -> Unit>()
        var closes = 0
        var recording = true
        val states = mutableListOf<DirectPeerStop.State>()
        val stop = DirectPeerStop({ task -> queue.add(task); true }, { closes++; true }, { states.add(it) })
        stop.request(); stop.request()
        assertEquals(DirectPeerStop.State.PENDING, stop.state())
        assertEquals(1, queue.size)
        assertTrue(recording)
        queue.single().invoke()
        assertEquals(DirectPeerStop.State.CLOSED, stop.state())
        assertEquals(1, closes)
        assertEquals(listOf(DirectPeerStop.State.PENDING, DirectPeerStop.State.CLOSED), states)
        assertTrue(recording)
        recording = false
        assertFalse(recording)
    }

    @Test fun `blocked native close never reports completion`() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val done = CountDownLatch(1)
        val stop = DirectPeerStop({ task -> Thread { try { task() } finally { done.countDown() } }.start(); true }, {
            entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); true
        })
        try {
            stop.request(); assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertEquals(DirectPeerStop.State.PENDING, stop.state())
            stop.request()
        } finally { release.countDown() }
        assertTrue(done.await(2, TimeUnit.SECONDS))
        assertEquals(DirectPeerStop.State.CLOSED, stop.state())
    }

    @Test fun `submission or native failure is not successful close`() {
        val states = mutableListOf<DirectPeerStop.State>()
        val notSubmitted = DirectPeerStop({ false }, { true }, { states.add(it) })
        notSubmitted.request(); assertEquals(DirectPeerStop.State.FAILED, notSubmitted.state())
        assertEquals(listOf(DirectPeerStop.State.PENDING, DirectPeerStop.State.FAILED), states)
        val failed = DirectPeerStop({ task -> task(); true }, { error("synthetic close failure") })
        failed.request(); assertEquals(DirectPeerStop.State.FAILED, failed.state())
        val absent = DirectPeerStop({ task -> task(); true }, { false })
        absent.request(); assertEquals(DirectPeerStop.State.NO_PEER, absent.state())
    }

    @Test fun `new pipeline cannot erase an old blocked peer close`() {
        val tracker = DirectPeerStopTracker()
        val old = Any(); val new = Any()
        tracker.update(old, DirectPeerStop.State.PENDING)
        tracker.beginSession()
        assertEquals(DirectPeerStop.State.PENDING, tracker.state.value)
        tracker.update(new, DirectPeerStop.State.PENDING)
        tracker.update(new, DirectPeerStop.State.CLOSED)
        assertEquals(DirectPeerStop.State.PENDING, tracker.state.value)
        tracker.update(old, DirectPeerStop.State.CLOSED)
        assertEquals(DirectPeerStop.State.CLOSED, tracker.state.value)
        tracker.beginSession()
        assertEquals(DirectPeerStop.State.IDLE, tracker.state.value)
    }

    @Test fun `failed or overflowing obligations remain uncertain`() {
        val tracker = DirectPeerStopTracker()
        val old = Any(); val new = Any()
        tracker.update(old, DirectPeerStop.State.PENDING)
        tracker.update(old, DirectPeerStop.State.FAILED)
        tracker.beginSession()
        assertEquals(DirectPeerStop.State.FAILED, tracker.state.value)
        tracker.update(new, DirectPeerStop.State.PENDING)
        tracker.update(new, DirectPeerStop.State.CLOSED)
        assertEquals(DirectPeerStop.State.FAILED, tracker.state.value)

        val bounded = DirectPeerStopTracker()
        val owners = List(33) { Any() }
        owners.forEach { bounded.update(it, DirectPeerStop.State.PENDING) }
        owners.forEach { bounded.update(it, DirectPeerStop.State.CLOSED) }
        assertEquals(DirectPeerStop.State.FAILED, bounded.state.value)
    }
}
