package de.kontrakurs.kontraprompter.cloud

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ProcessSessionFixtureTest {
    @Test fun `both clients share logout and late upload callback is rejected`() {
        val shared = ProcessSessionFixture()
        val login = shared.beginLogin()
        val firstClient = shared.complete(login, "synthetic", true)!!
        val secondClient = shared.snapshot()!!
        assertSame(firstClient, secondClient)
        var uploads = 0
        shared.logout()
        assertTrue(shared.loggedOut())
        assertNull(shared.snapshot())
        assertFalse(shared.applyIfCurrent(firstClient) { uploads++ })
        assertFalse(shared.applyIfCurrent(secondClient) { uploads++ })
        assertEquals(0, uploads)
    }

    @Test fun `late login and restore cannot undo explicit logout`() {
        val s = ProcessSessionFixture()
        val login = s.beginLogin(); s.logout()
        assertNull(s.complete(login, "synthetic-login", true))
        assertNull(s.beginRestore())
        val another = ProcessSessionFixture()
        val restore = another.beginRestore()!!; another.logout()
        assertNull(another.complete(restore, "synthetic-restore", true))
        assertTrue(s.loggedOut())
        assertNull(s.snapshot())
    }

    @Test fun `new attempt supersedes older login and verifies restore`() {
        val s = ProcessSessionFixture()
        val older = s.beginRestore()!!; val newer = s.beginLogin()
        assertNull(s.beginRestore()) // background restore must not supersede explicit login
        assertNull(s.complete(older, "older", true))
        assertNull(s.complete(newer, "not-verified", false))
        assertNull(s.snapshot())
        val verified = s.beginRestore()!!; val current = s.complete(verified, "verified", true)!!
        assertNull(s.beginRestore()) // an established session is not replaced by restore
        assertNull(s.complete(verified, "replayed", true))
        assertSame(current, s.snapshot())
    }

    @Test fun `late unauthorized does not invalidate newer session or its callback`() {
        val s = ProcessSessionFixture()
        val first = s.complete(s.beginLogin(), "old", true)!!
        val second = s.complete(s.beginLogin(), "new", true)!!
        assertFalse(s.unauthorized(first))
        var accepted = 0
        assertFalse(s.applyIfCurrent(first) { accepted++ })
        assertTrue(s.applyIfCurrent(second) { accepted++ })
        assertEquals(1, accepted)
        assertTrue(s.unauthorized(second))
        assertNull(s.snapshot())
        assertNull(s.beginRestore())
        assertNotNull(s.complete(s.beginLogin(), "explicit-new-login", true))
    }

    @Test fun `late attempt after newer verified login cannot replace it`() {
        val s = ProcessSessionFixture()
        val slow = s.beginLogin(); val quick = s.beginLogin()
        val session = s.complete(quick, "new", true)!!
        assertNull(s.complete(slow, "old", true))
        assertSame(session, s.snapshot())
    }

    @Test fun `concurrent logout and callback are serialized`() {
        repeat(100) {
            val s = ProcessSessionFixture(); val session = s.complete(s.beginLogin(), "synthetic", true)!!
            val entered = CountDownLatch(1); val finished = CountDownLatch(1)
            val commits = AtomicInteger()
            val worker = Thread { s.applyIfCurrent(session) { entered.countDown(); commits.incrementAndGet() }; finished.countDown() }
            worker.start(); assertTrue(entered.await(2, TimeUnit.SECONDS)); s.logout()
            assertTrue(finished.await(2, TimeUnit.SECONDS)); worker.join(1000)
            assertEquals(1, commits.get()); assertTrue(s.loggedOut())
            assertFalse(s.applyIfCurrent(session) { commits.incrementAndGet() })
        }
    }

    @Test fun `reentrant logout is refused and failed callback releases guard`() {
        val s = ProcessSessionFixture(); val session = s.complete(s.beginLogin(), "synthetic", true)!!
        try { s.applyIfCurrent(session) { s.logout() }; fail("reentrant mutation accepted") }
        catch (_: IllegalStateException) { }
        s.logout(); assertTrue(s.loggedOut())
    }
}
