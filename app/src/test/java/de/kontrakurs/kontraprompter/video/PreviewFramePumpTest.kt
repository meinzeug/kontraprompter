package de.kontrakurs.kontraprompter.video

import org.junit.Assert.*
import org.junit.Test
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class PreviewFramePumpTest {
    private class Buffer(
        private val beforeRetain: () -> Unit = {},
        private val onRelease: () -> Unit = {},
    ) : VideoFrame.Buffer {
        val retained = AtomicInteger()
        val released = AtomicInteger()
        override fun getWidth() = 2
        override fun getHeight() = 2
        override fun toI420(): VideoFrame.I420Buffer = error("unused")
        override fun cropAndScale(x: Int, y: Int, width: Int, height: Int, scaledWidth: Int, scaledHeight: Int): VideoFrame.Buffer = error("unused")
        override fun retain() { beforeRetain(); retained.incrementAndGet() }
        override fun release() { onRelease(); released.incrementAndGet() }
        fun frame() = VideoFrame(this, 0, 1L)
    }

    @Test fun `blocked downstream drops excess frames without making callers wait for that sink`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val browser = AtomicInteger()
        val pump = PreviewFramePump(VideoSink {
            browser.incrementAndGet(); entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
        })
        val first = Buffer(); val second = Buffer(); val overflow = List(100) { Buffer() }
        try {
            pump.onFrame(first.frame())
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            pump.onFrame(second.frame())
            val callers = Executors.newSingleThreadExecutor()
            try { callers.submit { overflow.forEach { pump.onFrame(it.frame()) } }.get(2, TimeUnit.SECONDS) }
            finally { callers.shutdownNow() }
            assertTrue(overflow.all { it.retained.get() == 0 })
            val drained = pump.revoke()
            assertFalse(drained.await(30, TimeUnit.MILLISECONDS))
            release.countDown()
            assertTrue(drained.await(5, TimeUnit.SECONDS))
            assertEquals(1, browser.get()) // queued frame is rejected by revoked barrier
            assertEquals(1, first.retained.get()); assertEquals(1, first.released.get())
            assertEquals(1, second.retained.get()); assertEquals(1, second.released.get())
            pump.onFrame(Buffer().frame())
            assertEquals(1, browser.get())
        } finally { release.countDown(); pump.revoke() }
    }

    @Test fun `revoke does not wait for retain and completion waits for its release`() {
        val retaining = CountDownLatch(1); val continueRetain = CountDownLatch(1)
        val frames = AtomicInteger()
        val pump = PreviewFramePump(VideoSink { frames.incrementAndGet() })
        val buffer = Buffer(beforeRetain = { retaining.countDown(); check(continueRetain.await(5, TimeUnit.SECONDS)) })
        val caller = Executors.newSingleThreadExecutor()
        try {
            val task = caller.submit { pump.onFrame(buffer.frame()) }
            assertTrue(retaining.await(5, TimeUnit.SECONDS))
            val drained = pump.revoke()
            assertFalse(drained.await(30, TimeUnit.MILLISECONDS))
            continueRetain.countDown(); task.get(5, TimeUnit.SECONDS)
            assertTrue(drained.await(5, TimeUnit.SECONDS))
            assertEquals(0, frames.get())
            assertEquals(1, buffer.retained.get()); assertEquals(1, buffer.released.get())
        } finally { continueRetain.countDown(); pump.revoke(); caller.shutdownNow() }
    }

    @Test fun `release failure fails closed and never reports verified drain`() {
        val entered = CountDownLatch(1)
        val pump = PreviewFramePump(VideoSink { entered.countDown() })
        val bad = Buffer(onRelease = { throw IllegalStateException("synthetic release failure") })
        pump.onFrame(bad.frame())
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val drained = pump.revoke()
        assertFalse(drained.await(100, TimeUnit.MILLISECONDS))
        val next = Buffer(); pump.onFrame(next.frame())
        assertEquals(0, next.retained.get())
    }

    @Test fun `downstream failure stops future delivery and releases accepted frame`() {
        val released = CountDownLatch(1)
        val pump = PreviewFramePump(VideoSink { throw IllegalStateException("synthetic") })
        val first = Buffer(onRelease = { released.countDown() }); pump.onFrame(first.frame())
        assertTrue(released.await(5, TimeUnit.SECONDS))
        // The failure, not an explicit call to revoke, must close admissions.
        val next = Buffer(); pump.onFrame(next.frame()); assertEquals(0, next.retained.get())
        val drained = pump.revoke()
        assertTrue(drained.await(5, TimeUnit.SECONDS))
        assertEquals(1, first.released.get())
    }

    @Test fun `retain failure makes future admission and verified drain impossible`() {
        val frames = AtomicInteger()
        val pump = PreviewFramePump(VideoSink { frames.incrementAndGet() })
        val bad = Buffer(beforeRetain = { throw IllegalStateException("synthetic retain failure") })
        pump.onFrame(bad.frame())
        val next = Buffer(); pump.onFrame(next.frame())
        assertEquals(0, next.retained.get())
        assertEquals(0, frames.get())
        assertFalse(pump.revoke().await(100, TimeUnit.MILLISECONDS))
    }

    @Test fun `downstream can revoke reentrantly without waiting for itself`() {
        val signaled = CountDownLatch(1)
        val result = AtomicReference<PreviewDrain>()
        lateinit var pump: PreviewFramePump
        pump = PreviewFramePump(VideoSink {
            val drained = pump.revoke()
            assertFalse(drained.isDrained)
            result.set(drained); signaled.countDown()
        })
        val frame = Buffer(); pump.onFrame(frame.frame())
        assertTrue(signaled.await(5, TimeUnit.SECONDS))
        assertTrue(result.get().await(5, TimeUnit.SECONDS))
        assertEquals(1, frame.released.get())
    }
}
