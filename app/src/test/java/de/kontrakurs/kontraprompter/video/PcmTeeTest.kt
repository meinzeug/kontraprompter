package de.kontrakurs.kontraprompter.video

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import org.junit.Assert.*
import org.junit.Test

class PcmTeeTest {
    @Test fun `subscriber sees an immutable snapshot and producer can reuse its input`() {
        val tee = PcmTee(); val a = tee.subscribe(); val b = tee.subscribe(); val bytes = byteArrayOf(1, 2, 3, 4)
        tee.publish("take", tee.generation, 32, 100, 48_000, bytes, bytes.size); bytes[0] = 99
        val first = a.poll()!!; val second = b.poll()!!
        assertTrue(first.samples.isReadOnly); assertEquals(1, first.samples.get().toInt()); assertEquals(1, second.samples.get().toInt())
        assertEquals(32L, first.firstSample); assertEquals(48_000, first.sampleRate)
        tee.close(); assertNull(a.poll()); assertNull(b.poll())
    }

    @Test fun `full queue permanently fails subscriber without changing the producer buffer`() {
        val tee = PcmTee(); val sub = tee.subscribe(1); val bytes = byteArrayOf(1, 2); val original = bytes.copyOf()
        tee.publish("take", tee.generation, 0, 100, 48_000, bytes, 2)
        tee.publish("take", tee.generation, 1, 200, 48_000, bytes, 2)
        assertEquals(1L, sub.dropped); assertArrayEquals(original, bytes); assertNull(sub.poll())
        tee.publish("take", tee.generation, 2, 300, 48_000, bytes, 2); assertNull(sub.poll())
        tee.close()
    }

    @Test fun `generation change fences buffered and in flight blocks`() {
        val tee = PcmTee(); val sub = tee.subscribe(); val old = tee.generation
        tee.publish("old", old, 0, 100, 48_000, byteArrayOf(1, 2), 2); tee.invalidate()
        tee.publish("old", old, 1, 200, 48_000, byteArrayOf(3, 4), 2); assertNull(sub.poll())
        tee.publish("new", tee.generation, 0, 300, 48_000, byteArrayOf(5, 6), 2)
        assertEquals("new", sub.poll()!!.recordingId); tee.close()
    }

    @Test(timeout = 5000) fun `contended analysis lock never makes publisher wait`() {
        val tee = PcmTee(); val sub = tee.subscribe()
        val field = sub.javaClass.getDeclaredField("lock").apply { isAccessible = true }; val lock = field.get(sub) as ReentrantLock
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val done = CountDownLatch(1)
        val analysis = Thread { lock.lock(); try { entered.countDown(); release.await() } finally { lock.unlock() } }
        analysis.isDaemon = true; analysis.start()
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            val publisher = Thread { tee.publish("take", tee.generation, 0, 100, 48_000, byteArrayOf(1, 2), 2); done.countDown() }
            publisher.isDaemon = true; publisher.start()
            assertTrue("audio publisher waited on analysis", done.await(1, TimeUnit.SECONDS)); assertEquals(1L, sub.dropped)
        } finally { release.countDown(); analysis.join(2000); tee.close() }
    }

    @Test fun `invalid PCM marks analysis failed and epoch overflow closes analysis`() {
        val tee = PcmTee(); val sub = tee.subscribe()
        tee.publish("take", tee.generation, 0, 1, 48_000, byteArrayOf(1), 1)
        assertEquals(1L, sub.dropped); assertNull(sub.poll()); tee.close()
        val other = PcmTee(); val another = other.subscribe()
        val field = other.javaClass.getDeclaredField("epoch").apply { isAccessible = true }
        (field.get(other) as AtomicLong).set(Long.MAX_VALUE)
        other.publish("take", other.generation, 0, 1, 48_000, byteArrayOf(1, 2), 2); other.invalidate()
        assertNull(another.poll()); assertThrows(IllegalStateException::class.java) { other.subscribe() }; other.close()
    }

    @Test fun `unexpected analysis disable fences previously queued blocks`() {
        val tee = PcmTee(); val sub = tee.subscribe(); val before = tee.generation
        tee.publish("take", before, 0, 1, 48_000, byteArrayOf(1, 2), 2)
        tee.disable(); assertNotEquals(before, tee.generation); assertNull(sub.poll())
        assertThrows(IllegalStateException::class.java) { tee.subscribe() }; tee.close()
    }

    @Test(timeout = 5000) fun `loss after dequeue while analysis lock is held invalidates block`() {
        val tee = PcmTee(); val sub = tee.subscribe(2)
        tee.publish("take", tee.generation, 0, 1, 48_000, byteArrayOf(1, 2), 2)
        val field = sub.javaClass.getDeclaredField("lock").apply { isAccessible = true }; val lock = field.get(sub) as ReentrantLock
        val dequeued = CountDownLatch(1); val lost = CountDownLatch(1)
        val result = java.util.concurrent.atomic.AtomicReference<Boolean?>(null)
        val analysis = Thread {
            lock.lock()
            try {
                val block = sub.poll() ?: return@Thread
                dequeued.countDown(); if (!lost.await(2, TimeUnit.SECONDS)) return@Thread
                result.set(sub.isCurrent(block))
            } finally { lock.unlock() }
        }
        analysis.isDaemon = true; analysis.start()
        try {
            assertTrue(dequeued.await(1, TimeUnit.SECONDS))
            tee.publish("take", tee.generation, 1, 1, 48_000, byteArrayOf(3, 4), 2)
            assertEquals(1L, sub.dropped); lost.countDown(); analysis.join(2000)
            assertFalse(analysis.isAlive); assertEquals(false, result.get())
        } finally { lost.countDown(); analysis.join(2000); tee.close() }
    }
}
