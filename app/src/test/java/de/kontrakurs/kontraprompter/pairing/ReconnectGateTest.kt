package de.kontrakurs.kontraprompter.pairing

import org.junit.Assert.*
import org.junit.Test

class ReconnectGateTest {
    private val peer = PeerBinding("33333333-3333-4333-8333-333333333333", "22222222-2222-4222-8222-222222222222", "c".repeat(64), RelayRole.CAMERA, 7, PeerTrustState.CONFIRMED)
    private var clock = 1000L
    private fun gate() = ReconnectGate(peer) { clock }
    private fun approve(g: ReconnectGate, a: ReconnectAttempt, revision: Long = 7, revoked: Boolean = false): Boolean {
        if (!g.serverStatus(a, a.challenge, peer.pairId, peer.peerDeviceId, peer.peerSpkiSha256, revision, revoked, 10_000)) return false
        return g.journalPersisted(a, peer.copy(serverRevision = revision))
    }

    @Test fun requiresFreshServerCheckAndPinnedMutualHandshake() {
        val g = gate(); val a = g.begin(true)!!
        assertFalse(g.tlsConfirmed(a, peer.peerSpkiSha256))
        assertTrue(approve(g, a)); assertEquals(ReconnectPhase.HANDSHAKING, g.snapshot().phase)
        assertTrue(g.tlsConfirmed(a, peer.peerSpkiSha256)); assertEquals(ReconnectPhase.READY, g.snapshot().phase)
        assertNull(g.begin(true))
    }
    @Test fun oldResultsCannotReviveLostOrStoppedConnection() {
        val g = gate(); val old = g.begin(true)!!
        assertTrue(g.isChecking(old))
        g.disconnected(old.generation, false); assertFalse(g.isChecking(old)); assertFalse(approve(g, old))
        val fresh = g.begin(true)!!; assertNotEquals(old.challenge, fresh.challenge)
        assertTrue(approve(g, fresh)); g.stop()
        assertFalse(g.tlsConfirmed(fresh, peer.peerSpkiSha256))
        assertEquals(ReconnectPhase.STOPPED, g.snapshot().phase)
    }
    @Test fun revokedOrChangedIdentityIsTerminal() {
        val a = gate(); val first = a.begin(true)!!
        assertFalse(approve(a, first, revoked = true)); assertEquals(ReconnectPhase.REVOKED, a.snapshot().phase)
        assertNull(a.begin(true))
        val b = gate(); val second = b.begin(true)!!
        assertFalse(b.serverStatus(second, second.challenge, peer.pairId, peer.peerDeviceId, "d".repeat(64), 7, false, 1000))
        assertEquals(ReconnectPhase.RECOVERY_REQUIRED, b.snapshot().phase); assertNull(b.begin(true))
    }
    @Test fun staleRevisionWrongChallengeAndExpiredLeaseFailClosed() {
        val g = gate(); val a = g.begin(true)!!
        assertFalse(g.serverStatus(a, "0".repeat(64), peer.pairId, peer.peerDeviceId, peer.peerSpkiSha256, 7, false, 1000))
        assertTrue(approve(g, a)); clock += 10_000
        assertFalse(g.tlsConfirmed(a, peer.peerSpkiSha256)); assertEquals(ReconnectPhase.RETRY, g.snapshot().phase)
        val b = g.begin(true)!!; assertFalse(approve(g, b, revision = 6)); assertEquals(ReconnectPhase.RECOVERY_REQUIRED, g.snapshot().phase)
    }
    @Test fun mismatchTlsPinNeverOpensSession() {
        val g = gate(); val a = g.begin(true)!!; assertTrue(approve(g, a))
        assertFalse(g.tlsConfirmed(a, "d".repeat(64))); assertEquals(ReconnectPhase.RECOVERY_REQUIRED, g.snapshot().phase)
    }
    @Test fun delayedServerResponseNeverExtendsTheLease() {
        val g = gate(); val a = g.begin(true)!!
        clock += 9_000
        assertTrue(approve(g, a))
        clock += 1_000
        assertFalse(g.tlsConfirmed(a, peer.peerSpkiSha256))
        assertEquals(ReconnectPhase.RETRY, g.snapshot().phase)
        val next = g.begin(true)!!; clock += 10_000
        assertFalse(approve(g, next))
        assertEquals(ReconnectPhase.RETRY, g.snapshot().phase)
    }

    @Test fun unpersistedOrMismatchedStatusCannotUnlockHandshake() {
        val g = gate(); val a = g.begin(true)!!
        assertTrue(g.serverStatus(a, a.challenge, peer.pairId, peer.peerDeviceId, peer.peerSpkiSha256, 8, false, 10_000))
        assertEquals(ReconnectPhase.PERSISTING_STATUS, g.snapshot().phase)
        assertFalse(g.tlsConfirmed(a, peer.peerSpkiSha256))
        assertFalse(g.journalPersisted(a, peer))
        assertEquals(ReconnectPhase.RECOVERY_REQUIRED, g.snapshot().phase)
    }

}
