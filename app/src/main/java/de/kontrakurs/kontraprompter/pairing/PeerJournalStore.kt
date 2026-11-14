package de.kontrakurs.kontraprompter.pairing

import android.content.Context
import android.util.AtomicFile
import java.io.File

/**
 * Crash-safe storage for public pairing metadata only. A local record is never
 * authorization: each connection still requires fresh authenticated server
 * status, matching TLS pins and a new channel confirmation.
 *
 * A missing or damaged journal never restores trust. Creating an empty one is
 * allowed only as part of an explicit new onboarding flow, not as recovery of
 * the previous relationship. Filesystem rollback cannot be detected here.
 */
internal class PeerJournalStore(context: Context) {
    companion object {
        private val lock = Any()
        private const val MAX_BYTES = 256 * 1024
    }

    private val directory = File(context.applicationContext.filesDir, "relay-pairing")
    private val base = File(directory, "peers.v1")
    private val backup = File(directory, "peers.v1.bak")
    private val pending = File(directory, "peers.v1.new")
    private val atomic = AtomicFile(base)

    private fun hasAnyFile() = base.exists() || backup.exists() || pending.exists()

    /** Null means no local trust; corrupt, mismatched or incomplete data fails closed. */
    fun load(ownerSha256: String, localDeviceId: String, localSpkiSha256: String): PeerJournal? = synchronized(lock) {
        loadLocked(ownerSha256, localDeviceId, localSpkiSha256)
    }

    private fun loadLocked(ownerSha256: String, localDeviceId: String, localSpkiSha256: String): PeerJournal? {
        if (!hasAnyFile()) return null
        check(base.exists() || backup.exists()) { "Unvollständiges Paarungsjournal; erneute Bestätigung erforderlich." }
        val bytes = atomic.openRead().use { input ->
            val buffer = ByteArray(MAX_BYTES + 1)
            var offset = 0
            while (offset < buffer.size) {
                val count = input.read(buffer, offset, buffer.size - offset)
                if (count < 0) break
                check(count > 0)
                offset += count
            }
            check(offset <= MAX_BYTES) { "Paarungsjournal ist zu groß." }
            buffer.copyOf(offset)
        }
        val journal = decodePeerJournal(bytes)
        check(journal.ownerSha256 == ownerSha256 && journal.localDeviceId == localDeviceId && journal.localSpkiSha256 == localSpkiSha256) {
            "Paarungsidentität stimmt nicht überein; erneute Bestätigung erforderlich."
        }
        return journal
    }

    /** The caller must first establish explicit new onboarding consent. */
    fun initializeAfterExplicitOnboarding(empty: PeerJournal) = synchronized(lock) {
        validatePeerJournal(empty)
        require(empty.peers.isEmpty())
        check(!hasAnyFile()) { "Paarungsjournal existiert bereits." }
        check(directory.isDirectory || directory.mkdirs()) { "Paarungsverzeichnis nicht verfügbar." }
        writeLocked(empty)
    }

    fun stage(owner: String, localId: String, localPin: String, candidate: PeerBinding) =
        update(owner, localId, localPin) { stagePeer(it, candidate) }

    fun approve(owner: String, localId: String, localPin: String, pairId: String, expectedPin: String, expectedRevision: Long) =
        update(owner, localId, localPin) { approvePeer(it, pairId, expectedPin, expectedRevision) }

    fun revoke(owner: String, localId: String, localPin: String, pairId: String) =
        update(owner, localId, localPin) { revokePeer(it, pairId) }

    fun applyAuthenticatedStatus(owner: String, localId: String, localPin: String, pairId: String, revision: Long, revoked: Boolean) =
        update(owner, localId, localPin) { applyAuthenticatedPeerStatus(it, pairId, revision, revoked) }

    /** Serializes read-modify-write and never creates an absent identity implicitly. */
    private fun update(ownerSha256: String, localDeviceId: String, localSpkiSha256: String, change: (PeerJournal) -> PeerJournal): PeerJournal = synchronized(lock) {
        val current = loadLocked(ownerSha256, localDeviceId, localSpkiSha256)
            ?: error("Paarungsjournal fehlt; erneute Bestätigung erforderlich.")
        val next = validatePeerJournal(change(current))
        require(next.ownerSha256 == current.ownerSha256 && next.localDeviceId == current.localDeviceId && next.localSpkiSha256 == current.localSpkiSha256)
        if (next != current) writeLocked(next)
        next
    }

    private fun writeLocked(value: PeerJournal) {
        val bytes = encodePeerJournal(validatePeerJournal(value))
        require(bytes.size <= MAX_BYTES)
        val output = atomic.startWrite()
        try {
            output.write(bytes)
            output.fd.sync()
            atomic.finishWrite(output)
        } catch (error: Exception) {
            atomic.failWrite(output)
            throw error
        }
    }
}
