package de.kontrakurs.kontraprompter.cloud

import android.content.Context
import de.kontrakurs.kontraprompter.video.SavedRecording
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

internal sealed interface CloudUploadState {
    data object Idle : CloudUploadState
    data class Running(val recordingId: String, val projectId: String, val sent: Long, val total: Long) : CloudUploadState
    data class Confirmed(val recordingId: String, val projectId: String, val continuation: Boolean = false) : CloudUploadState
    data class Failed(val recordingId: String, val projectId: String, val kind: String, val message: String) : CloudUploadState
}

/** Local byte changes are terminal until an explicit retry, not a transient network error. */
internal fun failedUploadPhase(kind: String): String = when (kind) {
    "project_missing" -> "project_missing"
    "conflict", "manifest_mismatch", "manifest_partial", "file_changed", RECORDING_DELETED -> "conflict"
    else -> "uncertain"
}

/** One local upload at a time; the worker owns its slot until its socket is closed. */
internal class CloudUploadCoordinator(context: Context, private val client: StudioClient) {
    companion object { private val gate = UploadOperationGate() }
    private val app = context.applicationContext
    private val _state = MutableStateFlow<CloudUploadState>(CloudUploadState.Idle)
    val state: StateFlow<CloudUploadState> = _state

    suspend fun upload(recording: SavedRecording, projectId: String, requiredSession: StudioClient.ManualUploadSession? = null): CloudUploadState {
        if (recording.partial) {
            val failure = CloudUploadState.Failed(recording.id, projectId, "partial", "Unvollständige Teilvideos können derzeit nur lokal exportiert werden.")
            _state.update { preserveActiveUpload(it, failure) }
            return failure
        }
        val ticket = gate.acquire() ?: run {
            val failure = CloudUploadState.Failed(recording.id, projectId, "busy", "Ein vorheriger Upload wird noch beendet.")
            _state.update { preserveActiveUpload(it, failure) }
            return failure
        }
        var entry: UploadEntry? = null
        val confirmedCommitted = AtomicBoolean(false)
        return try {
            _state.value = CloudUploadState.Running(recording.id, projectId, 0, recording.bytes)
            val startedEntry = withContext(Dispatchers.IO) { UploadJournal.begin(app, recording, projectId) }
            entry = startedEntry
            val receiptSession = client.uploadRecording(recording, startedEntry, { sent, total ->
                gate.deliverProgress(ticket) { _state.value = CloudUploadState.Running(recording.id, projectId, sent, total) }
            }, { gate.workerStarted(ticket) }, { gate.workerFinished(ticket) }, requiredSession)
            withContext(Dispatchers.IO) {
                client.withManualUploadSession(receiptSession) {
                    val current = UploadJournal.get(app, recording, startedEntry.projectId)
                    check(current != null && current.uploadId == startedEntry.uploadId && current.phase == "uploading") { "Uploadauftrag hat sich geändert." }
                    UploadJournal.write(app, startedEntry.copy(phase = "confirmed"))
                    confirmedCommitted.set(true)
                    CloudUploadState.Confirmed(recording.id, startedEntry.projectId, requiredSession != null).also { _state.value = it }
                }
            } ?: throw UploadException("auth", "Studio-Sitzung geändert; Serverbeleg prüfen. Die Aufnahme bleibt lokal.")
        } catch (error: CancellationException) {
            gate.stopProgress(ticket)
            _state.value = CloudUploadState.Failed(recording.id, projectId, "uncertain", "Upload abgebrochen oder unterbrochen; die lokale Aufnahme bleibt erhalten.")
            if (!confirmedCommitted.get()) entry?.let { withContext(NonCancellable + Dispatchers.IO) { runCatching { UploadJournal.write(app, it.copy(phase = "uncertain")) } } }
            throw error
        } catch (error: Exception) {
            gate.stopProgress(ticket)
            val failure = error as? UploadException
            val kind = failure?.kind ?: "uncertain"
            val currentEntry = entry
            if (kind == RECORDING_DELETED && currentEntry != null) {
                val bound = failure?.boundSession
                val result = if (bound == null) null else withContext(Dispatchers.IO) {
                    client.withManualUploadSession(bound) {
                        val saved = runCatching {
                            val current = UploadJournal.get(app, recording, currentEntry.projectId)
                            check(current != null && current.uploadId == currentEntry.uploadId && current.phase == "uploading") { "Uploadauftrag hat sich geändert." }
                            UploadJournal.write(app, currentEntry.copy(phase = "conflict", conflictReason = RECORDING_DELETED))
                        }.isSuccess
                        CloudUploadState.Failed(recording.id, projectId, kind,
                            deletedMessageAfterWrite(saved)).also { _state.value = it }
                    }
                }
                result ?: CloudUploadState.Failed(recording.id, projectId, "auth", "Studio-Sitzung geändert; Serverantwort prüfen. Die Aufnahme bleibt lokal.").also { _state.value = it }
            } else {
                val phase = failedUploadPhase(kind)
                currentEntry?.let { withContext(Dispatchers.IO) { runCatching { UploadJournal.write(app, it.copy(phase = phase, conflictReason = uploadConflictReason(kind, phase))) } } }
                CloudUploadState.Failed(recording.id, projectId, kind, error.message?.take(220) ?: "Upload nicht bestätigt; die lokale Aufnahme bleibt erhalten.").also { _state.value = it }
            }
        } finally { gate.operationFinished(ticket) }
    }
}
