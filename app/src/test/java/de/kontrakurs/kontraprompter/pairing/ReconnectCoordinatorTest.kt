package de.kontrakurs.kontraprompter.pairing

import org.junit.Assert.*
import org.junit.Test

/** A test-only event adapter: no socket, Android service, credentials or recording API. */
private class ReconnectFixture(initial: ByteArray, private val clock: () -> Long) {
    var journalBytes = initial.copyOf()
    private fun journal() = decodePeerJournal(journalBytes)
    private val peer = locallyConfirmedPeer(journal(), journal().peers.single().pairId)
        ?: error("Confirmed peer required")
    private val gate = ReconnectGate(peer, clock)
    var closes = 0
        private set
    var persist: (PeerJournal) -> Unit = { journalBytes = encodePeerJournal(it) }

    fun begin(network: Boolean) = gate.begin(network)
    fun phase() = gate.snapshot().phase
    fun state() = journal()

    /** A real adapter must authenticate and bind the response before calling this. */
    fun status(attempt: ReconnectAttempt, challenge: String = attempt.challenge,
               pin: String = peer.peerSpkiSha256, revision: Long = peer.serverRevision,
               revoked: Boolean = false): Boolean {
        if (!gate.isChecking(attempt) || challenge != attempt.challenge) return false
        val accepted = gate.serverStatus(attempt, challenge, peer.pairId, peer.peerDeviceId, pin, revision, revoked, 10_000)
        if (!accepted && gate.snapshot().phase != ReconnectPhase.REVOKED) return false
        val current = journal()
        if (locallyConfirmedPeer(current, peer.pairId) == null) return false
        val next = applyAuthenticatedPeerStatus(current, peer.pairId, revision, revoked)
        try { persist(next) } catch (_: Exception) { gate.stop(); return false }
        if (!accepted) return false
        return gate.journalPersisted(attempt, journal().peers.single { it.pairId == peer.pairId })
    }

    fun tls(attempt: ReconnectAttempt, pin: String = peer.peerSpkiSha256): Boolean = gate.tlsConfirmed(attempt, pin)
    fun ready(attempt: ReconnectAttempt): Boolean {
        if (gate.snapshot().phase != ReconnectPhase.READY) return false
        val current = locallyConfirmedPeer(journal(), peer.pairId)
        if (current == null || !gate.isReadyFor(attempt, current)) {
            gate.disconnected(attempt.generation, false)
            closes++
            return false
        }
        return true
    }
    fun disconnected(attempt: ReconnectAttempt) { gate.disconnected(attempt.generation, false); closes++ }
    fun stop() { gate.stop(); closes++ }
}

class ReconnectCoordinatorTest {
    private val peer = PeerBinding("33333333-3333-4333-8333-333333333333", "22222222-2222-4222-8222-222222222222", "c".repeat(64), RelayRole.CAMERA, 7, PeerTrustState.CONFIRMED)
    private val initial = PeerJournal("a".repeat(64), "11111111-1111-4111-8111-111111111111", "b".repeat(64), listOf(peer))
    private var time = 1_000L
    private fun fixture(bytes: ByteArray = encodePeerJournal(initial)) = ReconnectFixture(bytes) { time }

    @Test fun restartRequiresNewServerCheckAndFreshTlsEvenWithPersistedTrust() {
        val first = fixture(); val a = first.begin(true)!!
        assertTrue(first.status(a, revision = 8)); assertTrue(first.tls(a)); assertTrue(first.ready(a))
        first.stop()
        val restarted = fixture(first.journalBytes); val b = restarted.begin(true)!!
        assertNotEquals(a.challenge, b.challenge)
        assertFalse(restarted.tls(b)); assertFalse(restarted.ready(b))
        assertTrue(restarted.status(b, revision = 8)); assertTrue(restarted.tls(b))
    }

    @Test fun lostNetworkFencesLateServerAndTlsCallbacks() {
        val f = fixture(); val first = f.begin(true)!!
        f.disconnected(first); assertFalse(f.status(first)); assertFalse(f.tls(first))
        assertEquals(1, f.closes); assertNull(f.begin(false))
        val next = f.begin(true)!!; assertTrue(f.status(next)); f.disconnected(next)
        assertFalse(f.tls(next)); assertFalse(f.ready(next))
    }

    @Test fun authenticatedRevocationPersistsAndCannotReconnectAfterRestart() {
        val f = fixture(); val a = f.begin(true)!!
        assertFalse(f.status(a, revision = 8, revoked = true))
        assertEquals(PeerTrustState.REVOKED, f.state().peers.single().trust)
        assertNull(f.begin(true)); assertFalse(f.tls(a))
        assertThrows(IllegalStateException::class.java) { fixture(f.journalBytes) }
    }

    @Test fun failedRevocationPersistenceStillRequiresNewServerCheckAfterRestart() {
        val f = fixture(); val a = f.begin(true)!!
        f.persist = { error("synthetic disk failure") }
        assertFalse(f.status(a, revision = 8, revoked = true)); assertFalse(f.tls(a))
        val restarted = fixture(f.journalBytes); val b = restarted.begin(true)!!
        assertFalse(restarted.tls(b)); assertFalse(restarted.ready(b))
        assertFalse(restarted.status(b, revision = 8, revoked = true))
        assertEquals(PeerTrustState.REVOKED, restarted.state().peers.single().trust)
    }

    @Test fun staleResponseAndWrongPinNeverPersistOrUnlock() {
        val stale = fixture(); val a = stale.begin(true)!!
        assertFalse(stale.status(a, challenge = "0".repeat(64), revoked = true))
        assertEquals(PeerTrustState.CONFIRMED, stale.state().peers.single().trust)
        val wrong = fixture(); val b = wrong.begin(true)!!
        assertFalse(wrong.status(b, pin = "d".repeat(64)))
        assertEquals(ReconnectPhase.RECOVERY_REQUIRED, wrong.phase()); assertFalse(wrong.tls(b))
    }

    @Test fun persistFailureAndDisconnectDuringPersistenceDoNotAuthorizeTls() {
        val broken = fixture(); val a = broken.begin(true)!!
        broken.persist = { error("synthetic disk error") }
        assertFalse(broken.status(a, revision = 8)); assertFalse(broken.tls(a))
        assertEquals(7, broken.state().peers.single().serverRevision)
        val race = fixture(); val b = race.begin(true)!!
        race.persist = { value -> race.journalBytes = encodePeerJournal(value); race.disconnected(b) }
        assertFalse(race.status(b, revision = 8)); assertFalse(race.tls(b))
    }

    @Test fun localRevocationBeforeServerResponseRemainsRevoked() {
        val f = fixture(); val a = f.begin(true)!!
        f.journalBytes = encodePeerJournal(revokePeer(f.state(), peer.pairId))
        assertFalse(f.status(a)); assertFalse(f.tls(a))
        assertEquals(PeerTrustState.REVOKED, f.state().peers.single().trust)
    }

    @Test fun localRevocationClosesAnAlreadyReadyFixtureBeforeNextUse() {
        val f = fixture(); val a = f.begin(true)!!
        assertTrue(f.status(a)); assertTrue(f.tls(a)); assertTrue(f.ready(a))
        f.journalBytes = encodePeerJournal(revokePeer(f.state(), peer.pairId))
        assertFalse(f.ready(a)); assertEquals(1, f.closes)
    }

    @Test fun expiredLeaseAndCorruptJournalFailClosed() {
        val f = fixture(); val a = f.begin(true)!!
        assertTrue(f.status(a)); time += 10_000
        assertFalse(f.tls(a)); assertFalse(f.ready(a))
        assertThrows(IllegalArgumentException::class.java) { fixture(byteArrayOf(0)) }
    }
}
