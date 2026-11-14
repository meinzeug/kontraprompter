package de.kontrakurs.kontraprompter.video

import org.junit.Assert.*
import org.junit.Test
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class PreviewFrameBarrierTest {
    private fun frame() = VideoFrame(object : VideoFrame.Buffer {
        override fun getWidth() = 2
        override fun getHeight() = 2
        override fun toI420(): VideoFrame.I420Buffer = error("unused")
        override fun cropAndScale(x: Int, y: Int, width: Int, height: Int, scaledWidth: Int, scaledHeight: Int): VideoFrame.Buffer = error("unused")
        override fun retain() = Unit
        override fun release() = Unit
    }, 0, 1L)

    @Test fun `revocation blocks later frames but waits for an admitted blocked frame`() {
        val entered = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        val previewFrames = AtomicInteger()
        val recorderFrames = AtomicInteger()
        val phoneFrames = AtomicInteger()
        val barrier = PreviewFrameBarrier(VideoSink {
            previewFrames.incrementAndGet()
            entered.countDown()
            check(unblock.await(5, TimeUnit.SECONDS))
        })
        val worker = Executors.newSingleThreadExecutor()
        try {
            val first = worker.submit { barrier.onFrame(frame()) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val drained = barrier.revoke()
            assertFalse(drained.await(30, TimeUnit.MILLISECONDS))
            repeat(100) {
                val f = frame()
                barrier.onFrame(f)
                recorderFrames.incrementAndGet()
                phoneFrames.incrementAndGet()
            }
            assertEquals(1, previewFrames.get())
            assertEquals(100, recorderFrames.get())
            assertEquals(100, phoneFrames.get())
            assertSame(drained, barrier.revoke())
            unblock.countDown()
            first.get(5, TimeUnit.SECONDS)
            assertTrue(drained.await(5, TimeUnit.SECONDS))
            barrier.onFrame(frame())
            assertEquals(1, previewFrames.get())
        } finally { unblock.countDown(); worker.shutdownNow() }
    }

    @Test fun `drain completes after a sink failure and admissions never reopen`() {
        val barrier = PreviewFrameBarrier(VideoSink { throw IllegalStateException("synthetic") })
        assertThrows(IllegalStateException::class.java) { barrier.onFrame(frame()) }
        val drained = barrier.revoke()
        assertTrue(drained.await(1, TimeUnit.SECONDS))
        barrier.onFrame(frame())
    }

    @Test fun `all concurrent admitted frames must complete before drain`() {
        val entered = CountDownLatch(3)
        val release = CountDownLatch(1)
        val delivered = AtomicInteger()
        val barrier = PreviewFrameBarrier(VideoSink {
            delivered.incrementAndGet()
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
        })
        val worker = Executors.newFixedThreadPool(3)
        try {
            val tasks = List(3) { worker.submit { barrier.onFrame(frame()) } }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val drained = barrier.revoke()
            barrier.onFrame(frame())
            assertEquals(3, delivered.get())
            assertFalse(drained.await(30, TimeUnit.MILLISECONDS))
            release.countDown()
            tasks.forEach { it.get(5, TimeUnit.SECONDS) }
            assertTrue(drained.await(5, TimeUnit.SECONDS))
        } finally { release.countDown(); worker.shutdownNow() }
    }

    @Test fun `revocation before first frame is immediate and irreversible`() {
        val frames = AtomicInteger()
        val barrier = PreviewFrameBarrier(VideoSink { frames.incrementAndGet() })
        assertTrue(barrier.revoke().await(1, TimeUnit.SECONDS))
        repeat(10) { barrier.onFrame(frame()) }
        assertEquals(0, frames.get())
    }

    @Test fun `sink may revoke reentrantly and drain only completes after it returns`() {
        lateinit var barrier: PreviewFrameBarrier
        lateinit var signal: PreviewDrain
        var calls = 0
        barrier = PreviewFrameBarrier(VideoSink {
            calls++
            signal = barrier.revoke()
            assertFalse(signal.isDrained)
            barrier.onFrame(frame())
        })
        barrier.onFrame(frame())
        assertEquals(1, calls)
        assertTrue(signal.isDrained)
        assertTrue(signal.await(1, TimeUnit.SECONDS))
    }

    @Test fun `completion signal exposes no latch or countdown method`() {
        val signal = PreviewFrameBarrier(VideoSink { }).revoke()
        assertFalse(signal is CountDownLatch)
        assertFalse(signal.javaClass.methods.any { it.name == "countDown" })
    }
}
