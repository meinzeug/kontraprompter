package de.kontrakurs.kontraprompter.camera

import de.kontrakurs.kontraprompter.video.LiveSession
import de.kontrakurs.kontraprompter.video.LocalRecording
import de.kontrakurs.kontraprompter.video.isActive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class CameraReport(val phase: String, val recordingId: String?, val projectId: String?, val sizeBytes: Long? = null, val sha256: String? = null)
internal data class CameraAck(val id: String, val result: String, val revision: Long)
internal data class CameraPollRequest(val sequence: Long, val report: CameraReport, val acknowledgements: List<CameraAck>)
internal data class CameraPollResponse(val command: CameraCommand?, val remainingMs: Long)

/** A single owner serializes journal decisions and real recorder actions. */
internal class RemoteCameraController private constructor(private val journal: RemoteCameraJournal, private val session: LiveSession) {
    private val mutex = Mutex()
    private val poller = SerialCameraPoll()
    @Volatile private var closing = false

    /** The blocking transport must have finite connection/read timeouts and return only when its socket is closed. */
    suspend fun poll(exchange: (CameraPollRequest) -> CameraPollResponse): CameraReport =
        poller.poll(snapshot = { Unit }) {
            val report: CameraReport
            val acknowledgements: List<CameraAck>
            val sequence: Long
            try {
                sequence = withContext(Dispatchers.IO) { journal.reserveSequence() }
                report = snapshot()
                acknowledgements = withContext(Dispatchers.IO) { journal.snapshot().decisions.filter { it.pendingAck }.take(32).map { CameraAck(it.command.id, it.result, it.revision) } }
            } catch (error: CancellationException) { throw error }
            catch (error: CameraReconciliationPendingException) { throw error }
            catch (error: Exception) {
                // Without a readable durable journal we cannot receive or acknowledge a safe remote stop.
                session.stopRecording()
                throw error
            }
            val request = CameraPollRequest(sequence, report, acknowledgements)
            val started = android.os.SystemClock.elapsedRealtime()
            // A cancelled coroutine cannot release the poll lock while a socket worker remains alive.
            val response = withContext(NonCancellable + Dispatchers.IO) { exchange(request) }
            currentCoroutineContext().ensureActive()
            if (closing) return@poll report
            try { confirmReport(report, acknowledgements.associate { it.id to it.revision }) }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { session.stopRecording(); throw error }
            response.command?.let { command ->
                val elapsed = (android.os.SystemClock.elapsedRealtime() - started).coerceAtLeast(0)
                execute(command, response.remainingMs <= elapsed)
            }
            report
        }

    suspend fun close() = withContext(NonCancellable) {
        synchronized(ownerLock) { closing = true }
        // A stuck network transport must not keep an active recording running during explicit shutdown.
        session.stopRecording()
        mutex.withLock { session.stopRecording() }
        poller.closeAndDrain()
        session.recording.first { !it.isActive() }
        // Persist the final state before another session can claim this journal.
        try { snapshot() } catch (_: CameraReconciliationPendingException) { /* terminal state remains durable and is retried on reactivation */ }
        synchronized(ownerLock) { if (owner === this@RemoteCameraController) { session.setRemoteExclusive(false); owner = null } }
    }

    companion object {
        private val ownerLock = Any()
        private var owner: RemoteCameraController? = null
        fun open(journal: RemoteCameraJournal, session: LiveSession): RemoteCameraController = synchronized(ownerLock) {
            val current = owner
            if (current != null) {
                check(!current.closing && current.journal === journal && current.session === session) { "An earlier camera remote session is still shutting down" }
                current
            } else RemoteCameraController(journal, session).also { session.setRemoteExclusive(true); owner = it }
        }
    }

    private suspend fun execute(command: CameraCommand, expired: Boolean): CameraDecisionOutcome = mutex.withLock {
        // Persisting START and handing it to the recorder form one non-cancellable operation.
        withContext(NonCancellable) {
            check(!closing) { "Camera remote session is closing" }
            val outcome = try {
                withContext(Dispatchers.IO) { journal.decide(command, expired, session.canStartRemoteRecording()) }
            } catch (error: Exception) {
                // A failed durable ACK must not prevent a correctly targeted active camera from stopping.
                if (!expired && command.type == "record_stop") session.stopRecording(command.recordingId)
                else session.stopRecording()
                throw error
            }
            when (outcome.action) {
                "start" -> {
                    if (!session.startRecording(command.recordingId, command.projectId)) {
                        withContext(Dispatchers.IO) { journal.capture(command.recordingId, "error") }
                    }
                }
                "stop" -> session.stopRecording(command.recordingId)
            }
            outcome
        }
    }

    private suspend fun snapshot(): CameraReport = mutex.withLock {
        val state = session.recording.value
        val (phase, id) = when (state) {
            LocalRecording.Idle -> "idle" to null
            is LocalRecording.Preparing -> "preparing" to state.id
            is LocalRecording.Recording -> "recording" to state.id
            is LocalRecording.Finalizing -> "finalizing" to state.id
            is LocalRecording.Ready -> "ready" to state.id
            is LocalRecording.Partial -> "partial" to state.id
            is LocalRecording.Failed -> "error" to state.id
        }
        if (id != null && phase != "idle") withContext(Dispatchers.IO) { journal.adoptLocal(id, phase) }
        val book = withContext(Dispatchers.IO) { journal.snapshot() }
        val capture = book.captures.firstOrNull { it.recordingId == id }
        if (capture != null && (capture.phase != phase || (phase in setOf("ready", "partial") && capture.sizeBytes == null))) {
            try { withContext(Dispatchers.IO) {
                if (phase == "ready" || phase == "partial") journal.captureVerified(capture.recordingId, phase)
                else journal.capture(capture.recordingId, phase)
            } }
            catch (error: Exception) {
                if (phase in setOf("preparing", "recording")) session.stopRecording(capture.recordingId)
                throw error
            }
        }
        if (!withContext(Dispatchers.IO) { journal.reconcilePending() }) throw CameraReconciliationPendingException()
        val current = withContext(Dispatchers.IO) { journal.snapshot() }
        val matched = current.captures.firstOrNull { it.recordingId == id }
        if (phase in setOf("preparing", "recording", "finalizing")) return@withLock CameraReport(phase, id, matched?.projectId)
        val pending = current.captures.lastOrNull { it.phase in setOf("ready", "partial", "error") && it.reportedPhase != it.phase }
        if (pending != null) {
            if (pending.phase in setOf("ready", "partial")) check(pending.sizeBytes != null && pending.sha256 != null) { "Aufnahmebeleg fehlt" }
            CameraReport(pending.phase, pending.recordingId, pending.projectId, pending.sizeBytes, pending.sha256)
        }
        else if (id != null && matched == null) CameraReport(phase, id, null)
        else if (phase == "error" && id == null) CameraReport("error", null, null)
        else CameraReport("idle", null, null)
    }

    private suspend fun confirmReport(report: CameraReport, ackRevisions: Map<String, Long>) = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (report.recordingId != null && report.phase in setOf("ready", "partial", "error")) journal.confirmReport(report.recordingId, report.phase)
            journal.confirmAcks(ackRevisions)
        }
    }
}
