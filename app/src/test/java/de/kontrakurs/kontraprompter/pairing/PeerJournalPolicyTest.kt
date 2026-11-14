package de.kontrakurs.kontraprompter.pairing

import org.junit.Assert.*
import org.junit.Test

class PeerJournalPolicyTest {
    private val local = "11111111-1111-4111-8111-111111111111"
    private val peer = "22222222-2222-4222-8222-222222222222"
    private val pair = "33333333-3333-4333-8333-333333333333"
    private val empty = PeerJournal("a".repeat(64), local, "b".repeat(64))
    private val candidate = PeerBinding(pair, peer, "c".repeat(64), RelayRole.CAMERA, 7, PeerTrustState.PENDING)

    @Test fun candidateRequiresExplicitPinAndRevisionApproval() {
        val staged = stagePeer(empty, candidate)
        assertNull(locallyConfirmedPeer(staged, pair))
        assertThrows(IllegalArgumentException::class.java) { approvePeer(staged, pair, "d".repeat(64), 7) }
        assertThrows(IllegalArgumentException::class.java) { approvePeer(staged, pair, candidate.peerSpkiSha256, 8) }
        assertEquals(PeerTrustState.CONFIRMED, locallyConfirmedPeer(approvePeer(staged, pair, candidate.peerSpkiSha256, 7), pair)?.trust)
    }

    @Test fun revokedPairAndReplayedServerStatusNeverReactivate() {
        val approved = approvePeer(stagePeer(empty, candidate), pair, candidate.peerSpkiSha256, 7)
        val revoked = revokePeer(approved, pair)
        assertEquals(revoked, stagePeer(revoked, candidate))
        assertNull(locallyConfirmedPeer(applyAuthenticatedPeerStatus(revoked, pair, 9, false), pair))
        assertThrows(IllegalArgumentException::class.java) { approvePeer(revoked, pair, candidate.peerSpkiSha256, 7) }
    }

    @Test fun staleUpdatesCannotUndoOrReplaceTrust() {
        val approved = approvePeer(stagePeer(empty, candidate), pair, candidate.peerSpkiSha256, 7)
        assertEquals(approved, applyAuthenticatedPeerStatus(approved, pair, 6, true))
        val revoked = applyAuthenticatedPeerStatus(approved, pair, 8, true)
        assertNull(locallyConfirmedPeer(applyAuthenticatedPeerStatus(revoked, pair, 7, false), pair))
        assertThrows(IllegalArgumentException::class.java) { stagePeer(approved, candidate.copy(peerSpkiSha256 = "d".repeat(64))) }
    }

    @Test fun replacementDeviceCannotReuseIdentityWithDifferentPin() {
        val staged = stagePeer(empty, candidate)
        val newPair = candidate.copy(pairId = "44444444-4444-4444-8444-444444444444", peerSpkiSha256 = "d".repeat(64))
        assertThrows(IllegalArgumentException::class.java) { stagePeer(staged, newPair) }
        assertThrows(IllegalArgumentException::class.java) { stagePeer(empty, candidate.copy(peerDeviceId = local)) }
    }
}
