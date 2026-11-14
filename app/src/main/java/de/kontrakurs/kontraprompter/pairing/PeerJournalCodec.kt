package de.kontrakurs.kontraprompter.pairing

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

private const val MAGIC = 0x4b504a31 // KPJ1
private const val VERSION = 1
internal const val MAX_PEER_JOURNAL_BYTES = 256 * 1024

/** Contains only public identifiers and fingerprints; never a private key or session token. */
internal fun encodePeerJournal(journal: PeerJournal): ByteArray {
    validatePeerJournal(journal)
    val bytes = ByteArrayOutputStream()
    DataOutputStream(bytes).use { out ->
        out.writeInt(MAGIC); out.writeInt(VERSION)
        out.writeUTF(journal.ownerSha256); out.writeUTF(journal.localDeviceId); out.writeUTF(journal.localSpkiSha256)
        out.writeInt(journal.peers.size)
        for (peer in journal.peers) {
            out.writeUTF(peer.pairId); out.writeUTF(peer.peerDeviceId); out.writeUTF(peer.peerSpkiSha256)
            out.writeUTF(peer.localRole.wire); out.writeLong(peer.serverRevision); out.writeUTF(peer.trust.name)
        }
    }
    return bytes.toByteArray().also { require(it.size <= MAX_PEER_JOURNAL_BYTES) }
}

/** A malformed, truncated or future-version journal is rejected, never reset to empty trust. */
internal fun decodePeerJournal(bytes: ByteArray): PeerJournal {
    require(bytes.size in 1..MAX_PEER_JOURNAL_BYTES)
    try {
        val input = DataInputStream(ByteArrayInputStream(bytes))
        require(input.readInt() == MAGIC && input.readInt() == VERSION)
        val owner = input.readUTF(); val local = input.readUTF(); val pin = input.readUTF()
        val count = input.readInt(); require(count in 0..256)
        val peers = List(count) {
            val pair = input.readUTF(); val device = input.readUTF(); val peerPin = input.readUTF()
            val roleText = input.readUTF(); val role = RelayRole.entries.firstOrNull { it.wire == roleText } ?: error("Unbekannte Rolle")
            val revision = input.readLong(); val state = PeerTrustState.valueOf(input.readUTF())
            PeerBinding(pair, device, peerPin, role, revision, state)
        }
        require(input.read() == -1) { "Unerwartete Journaldaten." }
        return validatePeerJournal(PeerJournal(owner, local, pin, peers))
    } catch (error: IOException) {
        throw IllegalArgumentException("Paarungsjournal beschädigt.", error)
    }
}
