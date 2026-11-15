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
    init { client.onUploadSessionSet { _state.value = CloudUploadState.Idle } }

    suspend fun upload(recording: SavedRecording, projectId: String, requiredSession: StudioClient.ManualUploadSession? = null): CloudUploadState {
        fun changed() = CloudUploadState.Failed(recording.id, projectId, "session_changed", "Studio-Sitzung geändert; Serverstatus prüfen. Die Aufnahme bleibt lokal.")
        val bound = requiredSession ?: client.manualUploadSession() ?: run {
            val failure = CloudUploadState.Failed(recording.id, projectId, "auth", "Bitte erneut bei Videomaker anmelden.")
            return client.withoutUploadSession { _state.value = failure; failure } ?: changed()
        }
        if (!client.manualUploadSessionMatches(bound)) return changed()
        if (recording.partial) {
            val failure = CloudUploadState.Failed(recording.id, projectId, "partial", "Unvollständige Teilvideos können derzeit nur lokal exportiert werden.")
            client.withManualUploadSession(bound) { _state.update { preserveActiveUpload(it, failure) } }
            return failure
        }
        val ticket = gate.acquire() ?: run {
            val failure = CloudUploadState.Failed(recording.id, projectId, "busy", "Ein vorheriger Upload wird noch beendet.")
            client.withManualUploadSession(bound) { _state.update { preserveActiveUpload(it, failure) } }
            return failure
        }
        var entry: UploadEntry? = null
        val confirmedCommitted = AtomicBoolean(false)
        return try {
            val startedEntry = withContext(Dispatchers.IO) {
                client.withManualUploadSession(bound) {
                    _state.value = CloudUploadState.Running(recording.id, projectId, 0, recording.bytes)
                    UploadJournal.begin(app, recording, projectId)
                } ?: throw UploadException("session_changed", "Studio-Sitzung geändert; Serverstatus prüfen. Die Aufnahme bleibt lokal.")
            }
            entry = startedEntry
            val receiptSession = client.uploadRecording(recording, startedEntry, { sent, total ->
                gate.deliverProgress(ticket) { client.withManualUploadSession(bound) { _state.value = CloudUploadState.Running(recording.id, projectId, sent, total) } }
            }, { gate.workerStarted(ticket) }, { gate.workerFinished(ticket) }, bound)
            withContext(Dispatchers.IO) {
                client.withManualUploadSession(receiptSession) {
                    check(UploadJournal.replaceIfUnchanged(app, recording, startedEntry, startedEntry.copy(phase = "confirmed"))) { "Uploadauftrag hat sich geändert." }
                    confirmedCommitted.set(true)
                    CloudUploadState.Confirmed(recording.id, startedEntry.projectId, requiredSession != null).also { _state.value = it }
                }
            } ?: changed()
        } catch (error: CancellationException) {
            gate.stopProgress(ticket)
            if (!confirmedCommitted.get()) withContext(NonCancellable + Dispatchers.IO) {
                client.withManualUploadSession(bound) {
                    entry?.let { runCatching { UploadJournal.replaceIfUnchanged(app, recording, it, it.copy(phase = "uncertain")) } }
                    _state.value = CloudUploadState.Failed(recording.id, projectId, "uncertain", "Upload abgebrochen oder unterbrochen; die lokale Aufnahme bleibt erhalten.")
                }
            }
            throw error
        } catch (error: Exception) {
            gate.stopProgress(ticket)
            val failure = error as? UploadException
            val kind = failure?.kind ?: "uncertain"
            withContext(Dispatchers.IO) {
                client.withManualUploadSession(bound) {
                    val currentEntry = entry
                    val result = if (kind == RECORDING_DELETED && currentEntry != null) {
                        val saved = runCatching {
                            UploadJournal.replaceIfUnchanged(app, recording, currentEntry, currentEntry.copy(phase = "conflict", conflictReason = RECORDING_DELETED))
                        }
                        if (saved.getOrNull() == false) CloudUploadState.Failed(recording.id, projectId, "uncertain", "Uploadauftrag hat sich geändert; Serverstatus prüfen. Die Aufnahme bleibt lokal.")
                        else CloudUploadState.Failed(recording.id, projectId, kind, deletedMessageAfterWrite(saved.isSuccess))
                    } else {
                        val phase = failedUploadPhase(kind)
                        val notSaved = currentEntry?.let { runCatching { UploadJournal.replaceIfUnchanged(app, recording, it, it.copy(phase = phase, conflictReason = uploadConflictReason(kind, phase))) }.getOrNull() != true } == true
                        if (notSaved && failure?.invalidateSession == true) CloudUploadState.Failed(recording.id, projectId, "auth", "Sitzung abgelaufen. Uploadstatus konnte nicht sicher gespeichert werden; das Original bleibt lokal.")
                        else if (notSaved) CloudUploadState.Failed(recording.id, projectId, "uncertain", "Uploadstatus konnte nicht sicher gespeichert werden; Serverstatus prüfen. Die Aufnahme bleibt lokal.")
                        else CloudUploadState.Failed(recording.id, projectId, kind, error.message?.take(220) ?: "Upload nicht bestätigt; die lokale Aufnahme bleibt erhalten.")
                    }
                    _state.value = result
                    if (failure?.invalidateSession == true) client.invalidateManualUploadSession(bound)
                    result
                } ?: changed()
            }
        } finally { gate.operationFinished(ticket) }
    }
}
