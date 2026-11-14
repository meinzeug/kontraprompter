package de.kontrakurs.kontraprompter.video

import org.junit.Assert.*
import org.junit.Test
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class PreviewI420CopyPoolTest {
    private class I420(
        private val w: Int = 3,
        private val h: Int = 3,
        private val beforeWidth: () -> Unit = {},
        private val beforeY: () -> Unit = {},
        val y: ByteBuffer = plane(3, 3, 5, 10),
        val u: ByteBuffer = plane(2, 2, 3, 30),
        val v: ByteBuffer = plane(2, 2, 3, 50),
        private val yStride: Int = 5,
    ) : VideoFrame.I420Buffer {
        val retains = AtomicInteger()
        val releases = AtomicInteger()
        val conversions = AtomicInteger()
        override fun getWidth(): Int { beforeWidth(); return w }
        override fun getHeight() = h
        override fun getDataY(): ByteBuffer { beforeY(); return y.duplicate() }
        override fun getDataU() = u.duplicate()
        override fun getDataV() = v.duplicate()
        override fun getStrideY() = yStride
        override fun getStrideU() = 3
        override fun getStrideV() = 3
        override fun toI420(): VideoFrame.I420Buffer { conversions.incrementAndGet(); return this }
        override fun retain() { retains.incrementAndGet() }
        override fun release() { releases.incrementAndGet() }
        override fun cropAndScale(x: Int, y: Int, width: Int, height: Int, scaledWidth: Int, scaledHeight: Int): VideoFrame.Buffer = error("unused")
        fun frame() = VideoFrame(this, 90, 123456L)
    }

    companion object {
        private fun plane(width: Int, height: Int, stride: Int, initial: Int): ByteBuffer {
            val bytes = ByteBuffer.allocate(2 + stride * height)
            for (row in 0 until height) for (col in 0 until width) bytes.put(2 + row * stride + col, (initial + row * width + col).toByte())
            bytes.position(2); bytes.limit(2 + (height - 1) * stride + width)
            return bytes
        }
        private fun values(buffer: ByteBuffer): List<Int> {
            val copy = buffer.duplicate()
            return (0 until copy.remaining()).map { copy.get().toInt() and 255 }
        }
    }

    @Test fun `copies odd padded planes without retaining or modifying source`() {
        val source = I420(); val pool = PreviewI420CopyPool(3, 3, 2)
        val output = checkNotNull(pool.copy(source.frame()))
        try {
            val buffer = output.buffer as VideoFrame.I420Buffer
            assertTrue(buffer.dataY.isDirect)
            assertEquals(90, output.rotation); assertEquals(123456L, output.timestampNs)
            assertEquals(listOf(10,11,12,13,14,15,16,17,18), values(buffer.dataY))
            assertEquals(listOf(30,31,32,33), values(buffer.dataU))
            assertEquals(listOf(50,51,52,53), values(buffer.dataV))
            assertEquals(3, buffer.strideY); assertEquals(2, buffer.strideU); assertEquals(2, buffer.strideV)
            source.y.put(2, 99.toByte()); source.u.put(2, 88.toByte())
            assertEquals(10, buffer.dataY.get(0).toInt()); assertEquals(30, buffer.dataU.get(0).toInt())
            assertEquals(2, source.y.position()); assertEquals(2, source.u.position())
            assertEquals(0, source.retains.get()); assertEquals(0, source.releases.get()); assertEquals(0, source.conversions.get())
        } finally { output.release(); pool.close() }
    }

    @Test fun `held consumer exhausts bounded slots without retaining camera frame`() {
        val source = I420(); val pool = PreviewI420CopyPool(3, 3, 2)
        val first = checkNotNull(pool.copy(source.frame()))
        val second = checkNotNull(pool.copy(source.frame()))
        try {
            first.retain()
            first.release() // a retained downstream still owns this slot
            repeat(100) { assertNull(pool.copy(source.frame())) }
            assertEquals(0, source.retains.get()); assertEquals(0, source.conversions.get())
            first.release()
            val next = checkNotNull(pool.copy(source.frame()))
            next.release()
        } finally { second.release(); pool.close() }
    }

    @Test fun `blocked downstream keeps only copied slots and leaves borrowed frame unretained`() {
        val entered = CountDownLatch(1); val resume = CountDownLatch(1)
        val source = I420()
        val pool = PreviewI420CopyPool(3, 3, 2)
        val pump = PreviewFramePump(VideoSink {
            entered.countDown()
            check(resume.await(5, TimeUnit.SECONDS))
        })
        val callers = Executors.newSingleThreadExecutor()
        try {
            val first = checkNotNull(pool.copy(source.frame()))
            try { pump.onFrame(first) } finally { first.release() }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val second = checkNotNull(pool.copy(source.frame()))
            try { pump.onFrame(second) } finally { second.release() }
            callers.submit { repeat(100) { assertNull(pool.copy(source.frame())) } }.get(2, TimeUnit.SECONDS)
            assertEquals(0, source.retains.get()); assertEquals(0, source.conversions.get())
            val drained = pump.revoke()
            assertFalse(drained.await(30, TimeUnit.MILLISECONDS))
            resume.countDown()
            assertTrue(drained.await(5, TimeUnit.SECONDS))
            val next = checkNotNull(pool.copy(source.frame()))
            next.release()
        } finally {
            resume.countDown(); pump.revoke(); pool.close(); callers.shutdownNow()
        }
    }

    @Test fun `closed pool discards an in progress copy and does not publish after close`() {
        val entered = CountDownLatch(1); val resume = CountDownLatch(1)
        val source = I420(beforeY = { entered.countDown(); check(resume.await(5, TimeUnit.SECONDS)) })
        val pool = PreviewI420CopyPool(3, 3, 1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val result = executor.submit<VideoFrame?> { pool.copy(source.frame()) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            pool.close()
            resume.countDown()
            assertNull(result.get(5, TimeUnit.SECONDS))
            assertNull(pool.copy(I420().frame()))
            assertEquals(0, source.retains.get()); assertEquals(0, source.conversions.get())
        } finally { resume.countDown(); pool.close(); executor.shutdownNow() }
    }

    @Test fun `invalid plane and dimensions do not permanently consume a slot`() {
        val pool = PreviewI420CopyPool(3, 3, 1)
        try {
            assertNull(pool.copy(I420(yStride = 2).frame()))
            assertNull(pool.copy(I420(y = ByteBuffer.allocate(1)).frame()))
            assertNull(pool.copy(I420(w = 4).frame()))
            val valid = checkNotNull(pool.copy(I420().frame()))
            valid.release()
        } finally { pool.close() }
    }

    @Test fun `same byte budget accepts portrait dimensions`() {
        val pool = PreviewI420CopyPool(3, 2, 1)
        try {
            val output = checkNotNull(pool.copy(I420(w = 2, h = 3).frame()))
            try {
                val buffer = output.buffer as VideoFrame.I420Buffer
                assertEquals(2, buffer.width); assertEquals(3, buffer.height)
                assertEquals(listOf(10,11,13,14,16,17), values(buffer.dataY))
            } finally { output.release() }
        } finally { pool.close() }
    }

    @Test fun `close does not wait for admitted getter and rejects later getters`() {
        val entered = CountDownLatch(1); val resume = CountDownLatch(1)
        val blocked = I420(beforeWidth = { entered.countDown(); check(resume.await(5, TimeUnit.SECONDS)) })
        val laterCalls = AtomicInteger()
        val later = I420(beforeWidth = { laterCalls.incrementAndGet() })
        val pool = PreviewI420CopyPool(3, 3, 2)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val result = executor.submit<VideoFrame?> { pool.copy(blocked.frame()) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            pool.close() // must not wait for the external getter
            assertNull(pool.copy(later.frame()))
            assertEquals(0, laterCalls.get())
            resume.countDown()
            assertNull(result.get(5, TimeUnit.SECONDS))
        } finally { resume.countDown(); pool.close(); executor.shutdownNow() }
    }

    @Test fun `texture like input is rejected without invoking conversion`() {
        val calls = AtomicInteger()
        val texture = object : VideoFrame.Buffer {
            override fun getWidth() = 3
            override fun getHeight() = 3
            override fun toI420(): VideoFrame.I420Buffer { calls.incrementAndGet(); error("must not convert camera texture") }
            override fun retain() = Unit
            override fun release() = Unit
            override fun cropAndScale(x: Int, y: Int, width: Int, height: Int, scaledWidth: Int, scaledHeight: Int): VideoFrame.Buffer = error("unused")
        }
        val pool = PreviewI420CopyPool(3, 3, 1)
        try { assertNull(pool.copy(VideoFrame(texture, 0, 0))); assertEquals(0, calls.get()) }
        finally { pool.close() }
    }
}
