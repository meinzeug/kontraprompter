package de.kontrakurs.kontraprompter.video

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class RawFrameFanoutTest {
    @Test fun `same borrowed original reaches recorder before source once`() {
        val source = RawFrameFanout<Any>()
        val owner = Any()
        val frame = Any()
        val observed = mutableListOf<Pair<String, Any>>()
        assertTrue(source.attach(owner, { observed.add("raw" to it) }, { fail("unexpected failure") }))
        source.deliverThen(frame) { observed.add("source" to it) }
        assertEquals(listOf("raw", "source"), observed.map { it.first })
        assertTrue(observed.all { it.second === frame })
        source.detach(owner)
        source.deliverThen(frame) { observed.add("source" to it) }
        assertEquals(listOf("raw", "source", "source"), observed.map { it.first })
    }

    @Test fun `recorder exception is reported but source still receives frame`() {
        val source = RawFrameFanout<Int>()
        val errors = AtomicInteger()
        val forwarded = mutableListOf<Int>()
        assertTrue(source.attach(Any(), { error("recorder failed") }, { errors.incrementAndGet() }))
        source.deliverThen(7) { forwarded.add(it) }
        assertEquals(1, errors.get())
        assertEquals(listOf(7), forwarded)
    }

    @Test fun `detach fences in flight callback and excludes replacement until complete`() {
        val source = RawFrameFanout<Int>()
        val owner = Any()
        val next = Any()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val detached = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        assertTrue(source.attach(owner, { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }, { failure.set(AssertionError("unexpected failure")) }))
        val callback = Thread { try { source.deliver(1) } catch (error: Throwable) { failure.set(error) } }
        val stop = Thread { try { source.detach(owner) } catch (error: Throwable) { failure.set(error) } finally { detached.countDown() } }
        callback.start()
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            stop.start()
            assertFalse(detached.await(150, TimeUnit.MILLISECONDS))
            assertFalse(source.attach(next, {}, {}))
        } finally { release.countDown(); callback.join(5000); stop.join(5000) }
        assertFalse(callback.isAlive); assertFalse(stop.isAlive); assertNull(failure.get())
        assertTrue(source.attach(next, {}, {}))
        source.detach(owner) // a stale detach cannot remove a new subscriber
        assertFalse(source.attach(Any(), {}, {}))
        source.detach(next)
    }

    @Test fun `close and detach both wait for outstanding callback`() {
        val source = RawFrameFanout<Int>()
        val owner = Any()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val detached = CountDownLatch(1)
        assertTrue(source.attach(owner, { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }, {}))
        val callback = Thread { source.deliver(1) }
        val stop = Thread { source.detach(owner); detached.countDown() }
        val close = Thread { source.close(); closed.countDown() }
        callback.start()
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            stop.start(); close.start()
            assertFalse(detached.await(150, TimeUnit.MILLISECONDS))
            assertFalse(closed.await(150, TimeUnit.MILLISECONDS))
        } finally { release.countDown(); callback.join(5000); stop.join(5000); close.join(5000) }
        assertFalse(callback.isAlive); assertFalse(stop.isAlive); assertFalse(close.isAlive)
        assertFalse(source.attach(Any(), {}, {}))
    }
}
