package de.kontrakurs.kontraprompter.pairing

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

internal enum class ReconnectPhase { STOPPED, WAITING_NETWORK, CHECKING_SERVER, HANDSHAKING, READY, RETRY, REVOKED, RECOVERY_REQUIRED }
internal data class ReconnectAttempt(val generation: Long, val challenge: String)
internal data class ReconnectSnapshot(val phase: ReconnectPhase, val generation: Long, val attempt: Int)

/**
 * Fences asynchronous reconnect callbacks. The caller must authenticate server
 * responses over HTTPS and persist a confirmed revocation in PeerJournalStore.
 * READY authorizes signaling only; it never starts a camera recording.
 */
internal class ReconnectGate(private val peer: PeerBinding, private val nowMs: () -> Long) {
    private val random = SecureRandom()
    private var generation = 0L
    private var attemptCount = 0
    private var phase = ReconnectPhase.STOPPED
    private var challenge: String? = null
    private var leaseUntil = 0L
    private var lastRevision = peer.serverRevision
    private var terminal = false

    init {
        fun canonical(value: String) = runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)
        require(peer.trust == PeerTrustState.CONFIRMED && peer.serverRevision >= 0 && canonical(peer.pairId) && canonical(peer.peerDeviceId) && peer.peerSpkiSha256.matches(Regex("[a-f0-9]{64}")))
    }

    @Synchronized fun snapshot() = ReconnectSnapshot(phase, generation, attemptCount)

    /** Caller applies bounded backoff before retrying; no background loop starts here. */
    @Synchronized fun begin(networkAvailable: Boolean): ReconnectAttempt? {
        if (terminal || phase in setOf(ReconnectPhase.READY, ReconnectPhase.CHECKING_SERVER, ReconnectPhase.HANDSHAKING)) return null
        check(generation < Long.MAX_VALUE && attemptCount < Int.MAX_VALUE)
        generation++; challenge = null; leaseUntil = 0
        if (!networkAvailable) { phase = ReconnectPhase.WAITING_NETWORK; return null }
        attemptCount++
        val value = ByteArray(32).also(random::nextBytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        challenge = value; phase = ReconnectPhase.CHECKING_SERVER
        return ReconnectAttempt(generation, value)
    }

    /** Called only for an authenticated HTTPS server response bound to this challenge. */
    @Synchronized fun serverStatus(attempt: ReconnectAttempt, responseChallenge: String, pairId: String, peerId: String, peerPin: String, revision: Long, revoked: Boolean, ttlMs: Long): Boolean {
        if (phase != ReconnectPhase.CHECKING_SERVER || attempt.generation != generation || attempt.challenge != challenge || responseChallenge != challenge) return false
        if (pairId != peer.pairId || peerId != peer.peerDeviceId || peerPin != peer.peerSpkiSha256 || revision < lastRevision) {
            phase = ReconnectPhase.RECOVERY_REQUIRED; terminal = true; return false
        }
        lastRevision = revision
        if (revoked) { phase = ReconnectPhase.REVOKED; terminal = true; return false }
        if (ttlMs !in 1..60_000) { phase = ReconnectPhase.RETRY; return false }
        val now = nowMs()
        if (now < 0) { phase = ReconnectPhase.RETRY; return false }
        leaseUntil = try { Math.addExact(now, ttlMs) } catch (_: ArithmeticException) { phase = ReconnectPhase.RETRY; return false }
        phase = ReconnectPhase.HANDSHAKING
        return true
    }

    /** Pinned mutual TLS must already have completed on this exact attempt. */
    @Synchronized fun tlsConfirmed(attempt: ReconnectAttempt, observedPeerPin: String): Boolean {
        if (phase != ReconnectPhase.HANDSHAKING || attempt.generation != generation || attempt.challenge != challenge) return false
        if (nowMs() >= leaseUntil) { phase = ReconnectPhase.RETRY; return false }
        val same = MessageDigest.isEqual(peer.peerSpkiSha256.toByteArray(Charsets.US_ASCII), observedPeerPin.toByteArray(Charsets.US_ASCII))
        if (!same) { phase = ReconnectPhase.RECOVERY_REQUIRED; terminal = true; return false }
        challenge = null; phase = ReconnectPhase.READY; return true
    }

    /** Loss or stop fences any outstanding server/TLS callbacks. */
    @Synchronized fun disconnected(attemptGeneration: Long, networkAvailable: Boolean) {
        if (terminal || attemptGeneration != generation || phase == ReconnectPhase.STOPPED) return
        check(generation < Long.MAX_VALUE)
        generation++; challenge = null; leaseUntil = 0
        phase = if (networkAvailable) ReconnectPhase.RETRY else ReconnectPhase.WAITING_NETWORK
    }
    @Synchronized fun stop() {
        if (terminal) return
        check(generation < Long.MAX_VALUE)
        generation++; challenge = null; leaseUntil = 0; phase = ReconnectPhase.STOPPED
    }
}
