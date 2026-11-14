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

internal data class CameraReport(val phase: String, val recordingId: String?, val projectId: String?, val sizeBytes: Long? = null, val sha256: String? = null, val paused: Boolean? = null, val cameraFront: Boolean? = null, val cameraCanSwitch: Boolean = false)
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
                val selection = session.cameraSelection.value
                report = snapshot().copy(cameraFront = selection.front, cameraCanSwitch = selection.canSwitch && selection.front != null && !selection.switching && !session.recording.value.isActive())
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
            // Handle a typed response before returning to a possibly cancelled
            // logout scope; no ACK from the rejected poll is confirmed.
            val response = exchangeWithLateReportConflict(
                exchange = { exchange(request) },
                persist = { conflict -> mutex.withLock { check(journal.quarantineReport(conflict.deviceId, report)) { "Der Konflikt konnte nicht sicher zugeordnet werden." } }; Unit },
                onUnsafe = { session.stopRecording(); Unit },
            )
            currentCoroutineContext().ensureActive()
            if (response == null) return@poll report
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
        finishCameraRemote(
            stop = { session.stopRecording(); mutex.withLock { session.stopRecording() } },
            drain = { poller.closeAndDrain() },
            awaitRecorder = { session.recording.first { !it.isActive() }; Unit },
            persist = { try { snapshot() } catch (_: CameraReconciliationPendingException) { /* retry on reactivation */ }; Unit },
            release = { synchronized(ownerLock) { if (owner === this@RemoteCameraController) { session.setRemoteExclusive(false); owner = null } } },
        )
    }

    companion object {
        private val ownerLock = Any()
        private var owner: RemoteCameraController? = null
        fun <T> withIdentityReset(action: () -> T): T = synchronized(ownerLock) {
            check(owner == null) { "Die vorherige Kamera wird noch beendet." }
            action()
        }
        fun open(journal: RemoteCameraJournal, session: LiveSession): RemoteCameraController = synchronized(ownerLock) {
            val current = owner
            if (current != null) {
                check(!current.closing && current.journal === journal && current.session === session) { "An earlier camera remote session is still shutting down" }
                current
            } else {
                check(session.setRemoteExclusive(true)) { "Eine laufende Aufnahme gehört bereits einem anderen Steuergerät." }
                try { RemoteCameraController(journal, session).also { owner = it } }
                catch (error: Exception) { session.setRemoteExclusive(false); throw error }
            }
        }
    }

    private suspend fun execute(command: CameraCommand, expired: Boolean): CameraDecisionOutcome = mutex.withLock {
        // Persisting START and handing it to the recorder form one non-cancellable operation.
        withContext(NonCancellable) {
            check(!closing) { "Camera remote session is closing" }
            val outcome = try {
                val live = session.recording.value
                val canPause = live is LocalRecording.Recording && live.id == command.recordingId
                val canResume = live is LocalRecording.Paused && live.id == command.recordingId
                withContext(Dispatchers.IO) { journal.decide(command, expired, session.canStartRemoteRecording(), canPause, canResume, !live.isActive() && session.cameraSelection.value.canSwitch && !session.cameraSelection.value.switching) }
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
                "pause" -> session.pauseRemoteRecording(command.recordingId)
                "resume" -> session.resumeRemoteRecording(command.recordingId)
                "switch" -> session.switchRemoteCamera()
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
            is LocalRecording.Paused -> "recording" to state.id
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
        // Do not let unrelated unverified historical media block STOP delivery for an
        // active recording. Reconciliation is resumed once the recorder is terminal.
        if (cameraReportBeforeReconciliation(phase)) {
            val active = withContext(Dispatchers.IO) { journal.snapshot() }.captures.firstOrNull { it.recordingId == id }
            return@withLock CameraReport(phase, id, active?.projectId, paused = if (phase == "recording") state is LocalRecording.Paused else null)
        }
        if (!withContext(Dispatchers.IO) { journal.reconcilePending() }) throw CameraReconciliationPendingException()
        val current = withContext(Dispatchers.IO) { journal.snapshot() }
        val matched = current.captures.firstOrNull { it.recordingId == id }
        val pending = pendingCameraCaptureReport(current)
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

    internal suspend fun hasQuarantinedReports(): Boolean = withContext(Dispatchers.IO) {
        journal.snapshot().captures.any { it.quarantinedPhase != null }
    }

    internal suspend fun conflictDetails(): List<CameraConflictDetails> = withContext(Dispatchers.IO) {
        cameraConflictDetails(journal.snapshot())
    }
}
