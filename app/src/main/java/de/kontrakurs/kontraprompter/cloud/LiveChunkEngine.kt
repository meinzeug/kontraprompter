package de.kontrakurs.kontraprompter.cloud

import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID

// EXPERIMENTAL: no production transport, background scheduling, or UI is connected.
// The caller must persist this binding before any remote operation and fence it
// against the full-file uploader. A session hash is not an authentication token.
internal data class LiveChunkBinding(val recordingId: String, val projectId: String, val sessionHash: String) {
    init {
        require(UUID.fromString(recordingId).toString() == recordingId)
        require(UUID.fromString(projectId).toString() == projectId)
        require(sessionHash.matches(Regex("[0-9a-f]{64}")))
    }
    val uploadId: String get() = uploadIdFor(recordingId, projectId)
}
internal data class LivePart(val index: Int, val revision: Long, val expectedRevision: Long, val size: Int, val sha256: String, val state: String)
internal data class LivePage(val state: String, val finalSize: Long?, val finalSha256: String?, val parts: List<LivePart>, val hasMore: Boolean)
internal data class LivePartAck(val revision: Long, val sha256: String)
internal data class LiveSeal(val state: String, val published: Boolean, val size: Long, val sha256: String)
internal data class LiveReceipt(val id: String, val projectId: String, val size: Long, val sha256: String, val published: Boolean)
internal data class FinalOriginal(val size: Long, val sha256: String)

/** Authenticated implementation must bind every call to the local camera session, without transmitting credentials to its LAN peer. */
internal interface LiveChunkTransport {
    fun begin(binding: LiveChunkBinding): Int
    fun status(binding: LiveChunkBinding, after: Int?): LivePage
    fun put(binding: LiveChunkBinding, index: Int, expectedRevision: Long, bytes: ByteArray, sha256: String): LivePartAck
    fun seal(binding: LiveChunkBinding, original: FinalOriginal): LiveSeal
    fun commit(binding: LiveChunkBinding, original: FinalOriginal): LiveReceipt
}

/** A single attempt; retry by recreating the engine with the same durable binding and querying server state. */
internal class LiveChunkEngine(private val transport: LiveChunkTransport, private val sessionMatches: () -> Boolean) {
    companion object { const val CHUNK = 1024 * 1024; const val MAX_SIZE = 1024L * 1024 * 1024 }
    private var cached: Pair<LiveChunkBinding, Pair<LivePage, Map<Int, LivePart>>>? = null
    private fun authorized() { check(sessionMatches()) { "Studio session changed" } }
    private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun part(file: File, index: Int, size: Int): ByteArray {
        val result = ByteArray(size)
        RandomAccessFile(file, "r").use { it.seek(index.toLong() * CHUNK); it.readFully(result) }
        return result
    }
    private fun fileHash(file: File, size: Long): String {
        check(file.isFile && file.length() == size)
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buf = ByteArray(64 * 1024); var count = 0L
            while (true) { val n = stream.read(buf); if (n < 0) break; count += n; check(count <= size); digest.update(buf, 0, n) }
            check(count == size)
        }
        check(file.length() == size)
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    private fun pages(binding: LiveChunkBinding): Pair<LivePage, Map<Int, LivePart>> {
        var after: Int? = null; var first: LivePage? = null; val parts = linkedMapOf<Int, LivePart>()
        while (true) {
            authorized(); val page = transport.status(binding, after)
            if (first == null) first = page else check(page.state == first.state && page.finalSize == first.finalSize && page.finalSha256 == first.finalSha256)
            check(page.parts.size <= 16)
            for (p in page.parts) {
                check(p.index in 0 until (MAX_SIZE / CHUNK).toInt() && p.index > (after ?: -1) && p.index !in parts)
                check(p.revision in 0..9007199254740990L && p.expectedRevision in 0..9007199254740990L && p.size in 1..CHUNK && p.sha256.matches(Regex("[0-9a-f]{64}")) && p.state in setOf("ready", "writing", "invalid"))
                parts[p.index] = p; after = p.index
            }
            if (!page.hasMore) return checkNotNull(first) to parts
            check(page.parts.isNotEmpty())
        }
    }
    /** Sends full chunks only during recording. A stopped original must come from the verified recorder manifest. */
    fun transfer(binding: LiveChunkBinding, file: File, finalized: FinalOriginal? = null): LiveReceipt? = try {
        transferAttempt(binding, file, finalized)
    } catch (failure: Exception) {
        // A failed request may have committed remotely despite a lost acknowledgement.
        cached = null
        throw failure
    }
    private fun transferAttempt(binding: LiveChunkBinding, file: File, finalized: FinalOriginal?): LiveReceipt? {
        authorized(); check(transport.begin(binding) == CHUNK)
        // On a live poll, reuse acknowledged state in this process. Restart, error and
        // finalization always re-query and rehash the remote parts. This avoids hashing
        // the entire growing video on every poll.
        val snapshot = if (finalized == null) cached?.takeIf { it.first == binding }?.second ?: pages(binding) else pages(binding)
        val (state, previous) = snapshot
        val existing = previous.toMutableMap()
        val size = finalized?.size ?: file.length()
        check(size in 1..MAX_SIZE && file.isFile && file.length() >= size)
        if (finalized != null) {
            check(finalized.sha256.matches(Regex("[0-9a-f]{64}")) && fileHash(file, size) == finalized.sha256)
        }
        if (state.state == "published") return null // never recreate a server copy deleted by the user
        if (state.state == "sealed") {
            check(finalized != null && state.finalSize == size && state.finalSha256 == finalized.sha256)
            return finish(binding, file, finalized)
        }
        check(state.state == "open")
        val count = if (finalized == null) (size / CHUNK).toInt() else ((size + CHUNK - 1) / CHUNK).toInt()
        check(existing.keys.all { it < count }) { "Server has unexpected extra chunks" }
        for (index in 0 until count) {
            val length = if (finalized != null) minOf(CHUNK.toLong(), size - index.toLong() * CHUNK).toInt() else CHUNK
            val prior = existing[index]
            if (finalized == null && prior?.state == "ready" && prior.size == length) continue
            val bytes = part(file, index, length)
            // Active MediaMuxer output can be rewritten. Do not send an unstable read.
            check(bytes.contentEquals(part(file, index, length))) { "Original changed while reading" }
            val sha = hash(bytes)
            if (prior?.state == "ready" && prior.size == length && prior.sha256 == sha) continue
            val revision = when (prior?.state) { null -> 0L; "writing" -> prior.expectedRevision; else -> prior.revision }
            authorized(); val ack = transport.put(binding, index, revision, bytes, sha)
            check(ack.revision == revision + 1 && ack.sha256 == sha)
            existing[index] = LivePart(index, ack.revision, revision, length, sha, "ready")
        }
        if (finalized == null) { cached = binding to (state to existing.toMap()); return null }
        cached = null
        check(fileHash(file, size) == finalized.sha256) { "Final original changed" }
        authorized(); val sealed = transport.seal(binding, finalized)
        check(sealed.state == "sealed" && !sealed.published && sealed.size == size && sealed.sha256 == finalized.sha256)
        return finish(binding, file, finalized)
    }
    private fun finish(binding: LiveChunkBinding, file: File, original: FinalOriginal): LiveReceipt {
        check(fileHash(file, original.size) == original.sha256)
        authorized(); val receipt = transport.commit(binding, original)
        authorized()
        check(receipt.published && receipt.id == binding.uploadId && receipt.projectId == binding.projectId && receipt.size == original.size && receipt.sha256 == original.sha256)
        return receipt
    }
}
