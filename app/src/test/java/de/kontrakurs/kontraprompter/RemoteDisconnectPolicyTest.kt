package de.kontrakurs.kontraprompter

import org.junit.Assert.*
import org.junit.Test

class RemoteDisconnectPolicyTest {
    @Test fun `only connected nonpending terminal camera status permits disconnect`() {
        for (phase in listOf("preparing", "recording", "paused", "finalizing", "failed", "unknown"))
            assertFalse(mayDisconnectRemote(ready = true, pending = false, phase = phase))
        for (phase in listOf("idle", "ready", "partial")) {
            assertTrue(mayDisconnectRemote(ready = true, pending = false, phase = phase))
            assertFalse(mayDisconnectRemote(ready = true, pending = true, phase = phase))
        }
        assertFalse(mayDisconnectRemote(ready = false, pending = false, phase = "unknown"))
    }

    @Test fun `recovery must still match session generation and statuses`() {
        val session = Any(); val snapshot = RecoverySnapshot(session, 3L, "offline", "unknown")
        assertTrue(snapshot.matches(session, 3L, "offline", "unknown"))
        assertFalse(snapshot.matches(Any(), 3L, "offline", "unknown"))
        assertFalse(snapshot.matches(session, 4L, "offline", "unknown"))
        assertFalse(snapshot.matches(session, 3L, "connected", "unknown"))
        assertFalse(snapshot.matches(session, 3L, "offline", "recording"))
        assertFalse(mayStartCamera(true)); assertTrue(mayStartCamera(false))
    }
}
