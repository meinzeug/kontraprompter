package de.kontrakurs.kontraprompter.cloud

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class SessionCookieSerializationTest {
    @Test fun `session change cannot cross the guarded durable decision`() {
        val cookie = SessionCookie(); cookie.set("old")
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val setterEntered = CountDownLatch(1); val setterFinished = CountDownLatch(1)
        val writer = thread {
            cookie.withCurrent("old") { entered.countDown(); check(release.await(3, TimeUnit.SECONDS)); "saved" }
        }
        assertTrue(entered.await(3, TimeUnit.SECONDS))
        val changer = thread { setterEntered.countDown(); cookie.set("new"); setterFinished.countDown() }
        assertTrue(setterEntered.await(3, TimeUnit.SECONDS))
        try { assertFalse(setterFinished.await(100, TimeUnit.MILLISECONDS)) }
        finally { release.countDown(); writer.join(3000); changer.join(3000) }
        assertTrue(setterFinished.await(3, TimeUnit.SECONDS))
        var wrote = false
        assertNull(cookie.withCurrent("old") { wrote = true })
        assertFalse(wrote); assertEquals("new", cookie.get())
    }
}
