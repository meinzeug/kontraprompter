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
        val stop = DirectPeerStop({ task -> queue.add(task); true }, { closes++ })
        stop.request(); stop.request()
        assertEquals(DirectPeerStop.State.PENDING, stop.state())
        assertEquals(1, queue.size)
        assertTrue(recording)
        queue.single().invoke()
        assertEquals(DirectPeerStop.State.CLOSED, stop.state())
        assertEquals(1, closes)
        assertTrue(recording)
        recording = false
        assertFalse(recording)
    }

    @Test fun `blocked native close never reports completion`() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val done = CountDownLatch(1)
        val stop = DirectPeerStop({ task -> Thread { try { task() } finally { done.countDown() } }.start(); true }, {
            entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
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
        val notSubmitted = DirectPeerStop({ false }, {})
        notSubmitted.request(); assertEquals(DirectPeerStop.State.FAILED, notSubmitted.state())
        val failed = DirectPeerStop({ task -> task(); true }, { error("synthetic close failure") })
        failed.request(); assertEquals(DirectPeerStop.State.FAILED, failed.state())
    }
}
