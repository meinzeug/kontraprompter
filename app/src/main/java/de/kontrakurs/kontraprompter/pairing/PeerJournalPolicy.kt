package de.kontrakurs.kontraprompter.pairing

import java.util.UUID

/** Local record only. Fresh authenticated server revocation and TLS checks remain mandatory. */
internal enum class PeerTrustState { PENDING, CONFIRMED, REVOKED }
internal data class PeerBinding(
    val pairId: String,
    val peerDeviceId: String,
    val peerSpkiSha256: String,
    val localRole: RelayRole,
    val serverRevision: Long,
    val trust: PeerTrustState,
)
internal data class PeerJournal(
    val ownerSha256: String,
    val localDeviceId: String,
    val localSpkiSha256: String,
    val peers: List<PeerBinding> = emptyList(),
)

private const val MAX_PEERS = 256
private val HASH = Regex("^[a-f0-9]{64}$")
private fun canonicalId(value: String): Boolean = runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)

/** Validate on every disk read as well as before every mutation. */
internal fun validatePeerJournal(journal: PeerJournal): PeerJournal {
    require(HASH.matches(journal.ownerSha256) && canonicalId(journal.localDeviceId) && HASH.matches(journal.localSpkiSha256))
    require(journal.peers.size <= MAX_PEERS)
    val pairIds = mutableSetOf<String>()
    val devicePins = mutableMapOf<String, String>()
    val pinDevices = mutableMapOf<String, String>()
    for (peer in journal.peers) {
        require(canonicalId(peer.pairId) && pairIds.add(peer.pairId) && canonicalId(peer.peerDeviceId))
        require(peer.peerDeviceId != journal.localDeviceId && HASH.matches(peer.peerSpkiSha256))
        require(peer.peerSpkiSha256 != journal.localSpkiSha256 && peer.serverRevision >= 0)
        val prior = devicePins.putIfAbsent(peer.peerDeviceId, peer.peerSpkiSha256)
        require(prior == null || prior == peer.peerSpkiSha256) { "Geräteidentität hat sich geändert." }
        val priorDevice = pinDevices.putIfAbsent(peer.peerSpkiSha256, peer.peerDeviceId)
        require(priorDevice == null || priorDevice == peer.peerDeviceId) { "Geräteschlüssel wird für mehrere Identitäten verwendet." }
    }
    return journal
}

/** Recording a candidate never grants trust; the caller must first verify its pairing transcript. */
internal fun stagePeer(journal: PeerJournal, candidate: PeerBinding): PeerJournal {
    validatePeerJournal(journal)
    require(candidate.trust == PeerTrustState.PENDING)
    val existing = journal.peers.firstOrNull { it.pairId == candidate.pairId }
    if (existing != null) {
        require(existing.copy(trust = PeerTrustState.PENDING) == candidate) { "Paarungs-ID wurde verändert." }
        return journal // Includes revoked entries: a replay cannot reactivate one.
    }
    return validatePeerJournal(journal.copy(peers = journal.peers + candidate))
}

/** Explicit approval must refer to the exact previously displayed pin and revision. */
internal fun approvePeer(journal: PeerJournal, pairId: String, expectedPin: String, expectedRevision: Long): PeerJournal {
    validatePeerJournal(journal)
    val peer = journal.peers.firstOrNull { it.pairId == pairId } ?: error("Paarung nicht bekannt.")
    require(peer.peerSpkiSha256 == expectedPin && peer.serverRevision == expectedRevision) { "Paarungsdaten haben sich geändert." }
    require(peer.trust != PeerTrustState.REVOKED) { "Widerrufene Paarung benötigt eine neue Bestätigung." }
    if (peer.trust == PeerTrustState.CONFIRMED) return journal
    return journal.copy(peers = journal.peers.map { if (it.pairId == pairId) it.copy(trust = PeerTrustState.CONFIRMED) else it })
}

/** Local revocation is durable and cannot be undone by a later server 'active' response. */
internal fun revokePeer(journal: PeerJournal, pairId: String): PeerJournal {
    validatePeerJournal(journal)
    require(journal.peers.any { it.pairId == pairId }) { "Paarung nicht bekannt." }
    return journal.copy(peers = journal.peers.map { if (it.pairId == pairId) it.copy(trust = PeerTrustState.REVOKED) else it })
}

/** Caller must authenticate the server response; old revisions never roll local state back. */
internal fun applyAuthenticatedPeerStatus(journal: PeerJournal, pairId: String, revision: Long, revoked: Boolean): PeerJournal {
    validatePeerJournal(journal); require(revision >= 0)
    val peer = journal.peers.firstOrNull { it.pairId == pairId } ?: error("Paarung nicht bekannt.")
    if (revision < peer.serverRevision) return journal
    val next = peer.copy(serverRevision = revision, trust = if (revoked) PeerTrustState.REVOKED else peer.trust)
    return journal.copy(peers = journal.peers.map { if (it.pairId == pairId) next else it })
}

/** This is only local consent, not permission to connect or proof of current server status. */
internal fun locallyConfirmedPeer(journal: PeerJournal, pairId: String): PeerBinding? =
    validatePeerJournal(journal).peers.firstOrNull { it.pairId == pairId && it.trust == PeerTrustState.CONFIRMED }
