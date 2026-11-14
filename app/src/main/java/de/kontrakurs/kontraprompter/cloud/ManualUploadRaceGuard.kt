package de.kontrakurs.kontraprompter.cloud

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job

/**
 * Process-wide ordering between explicit starts, automatic retries and pause.
 * The caller performs durable operations on an IO dispatcher; the lock must not
 * span network work. Pausing cannot retract bytes already accepted by a server.
 */
internal class ManualUploadRaceGuard {
    private val lock = Any()
    private var revision = 0L
    private val inhibited = mutableSetOf<String>()
    private var activeId: String? = null
    private var activeJob: Job? = null

    fun explicitStart(recordingId: String): Long = synchronized(lock) {
        revision++
        if (activeId == recordingId) activeJob?.cancel(CancellationException("Upload wird pausiert"))
        revision
    }

    fun snapshot(): Long = synchronized(lock) { revision }

    /** Only an explicit, successfully persisted start may clear an IO-failure barrier. */
    fun persistExplicit(token: Long, recordingId: String, write: () -> Unit): Boolean = synchronized(lock) {
        if (token != revision) return@synchronized false
        inhibited.add(recordingId)
        write()
        inhibited.remove(recordingId)
        true
    }

    /** Recheck both the durable instruction and revision immediately before starting. */
    fun register(token: Long, recordingId: String, job: Job, verify: () -> Boolean): Boolean = synchronized(lock) {
        if (token != revision || recordingId in inhibited || activeJob != null || !job.isActive || !verify()) return@synchronized false
        activeId = recordingId; activeJob = job; true
    }

    /** Invalidation and cancellation happen before the durable pause attempt. */
    fun pause(recordingId: String, write: () -> Boolean): Boolean = synchronized(lock) {
        revision++
        inhibited.add(recordingId)
        if (activeId == recordingId) activeJob?.cancel(CancellationException("Upload wird pausiert"))
        val result = write() // On failure keep this recording inhibited in this process.
        inhibited.remove(recordingId)
        result
    }

    fun release(job: Job) = synchronized(lock) {
        if (activeJob === job) { activeId = null; activeJob = null }
    }
}
