package de.kontrakurs.kontraprompter.cloud

import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.runBlocking

/** Exercises actual StudioClient ownership and snapshot operations without any network. */
class ScopedStudioClientTest {
    @Test fun `experimental shared clients cannot open network operations`() {
        val registry = SessionRegistry()
        for (owner in SessionOwner.entries) {
            try { StudioClient(registry, owner).checkNetworkAllowed(); fail("unfenced network enabled") }
            catch (_: IllegalStateException) { }
            try { runBlocking { StudioClient(registry, owner).login("synthetic") }; fail("shared login reached network") }
            catch (_: IllegalStateException) { }
        }
        StudioClient().checkNetworkAllowed() // legacy isolated client remains usable
    }
    @Test fun `same owner shares session while camera stays independent`() {
        val registry = SessionRegistry()
        val one = StudioClient(registry, SessionOwner.STUDIO_UPLOAD)
        val two = StudioClient(registry, SessionOwner.STUDIO_UPLOAD)
        val camera = StudioClient(registry, SessionOwner.CAMERA_REMOTE)
        assertTrue(one.cookie.complete(one.cookie.beginLogin(), "test-studio"))
        assertTrue(two.hasSession())
        assertFalse(camera.hasSession())
        assertTrue(camera.cookie.complete(camera.cookie.beginLogin(), "test-camera"))
        one.cookie.logoutLocal()
        assertFalse(two.hasSession())
        assertTrue(camera.hasSession())
    }

    @Test fun `late login and old 401 cannot replace or invalidate current session`() {
        val registry = SessionRegistry()
        val one = StudioClient(registry, SessionOwner.STUDIO_UPLOAD)
        val two = StudioClient(registry, SessionOwner.STUDIO_UPLOAD)
        val oldAttempt = one.cookie.beginLogin()
        val newAttempt = two.cookie.beginLogin()
        assertTrue(two.cookie.complete(newAttempt, "test-value"))
        val oldSnapshot = two.cookie.get()!!
        assertFalse(one.cookie.complete(oldAttempt, "older-response"))
        assertTrue(one.cookie.complete(one.cookie.beginLogin(), "test-value")) // identical bearer, different generation
        two.cookie.invalidate(oldSnapshot)
        assertTrue(one.hasSession())
        val delayed = two.cookie.beginLogin()
        one.cookie.logoutLocal()
        assertFalse(two.cookie.complete(delayed, "late-response"))
        assertFalse(one.hasSession())
    }

    @Test fun `upload snapshot rejects logout and same cookie re-login`() {
        val registry = SessionRegistry()
        val one = StudioClient(registry, SessionOwner.STUDIO_UPLOAD)
        val two = StudioClient(registry, SessionOwner.STUDIO_UPLOAD)
        assertTrue(one.cookie.complete(one.cookie.beginLogin(), "same-bearer"))
        val upload = one.manualUploadSession()!!
        assertTrue(two.manualUploadSessionMatches(upload))
        two.cookie.logoutLocal()
        assertFalse(one.manualUploadSessionMatches(upload))
        assertTrue(two.cookie.complete(two.cookie.beginLogin(), "same-bearer"))
        assertFalse(one.manualUploadSessionMatches(upload))
    }
}
