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

internal sealed interface CloudUploadState {
    data object Idle : CloudUploadState
    data class Running(val recordingId: String, val projectId: String, val sent: Long, val total: Long) : CloudUploadState
    data class Confirmed(val recordingId: String, val projectId: String, val continuation: Boolean = false) : CloudUploadState
    data class Failed(val recordingId: String, val projectId: String, val kind: String, val message: String) : CloudUploadState
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
        return try {
            _state.value = CloudUploadState.Running(recording.id, projectId, 0, recording.bytes)
            entry = withContext(Dispatchers.IO) { UploadJournal.begin(app, recording, projectId) }
            client.uploadRecording(recording, entry, { sent, total ->
                gate.deliverProgress(ticket) { _state.value = CloudUploadState.Running(recording.id, projectId, sent, total) }
            }, { gate.workerStarted(ticket) }, { gate.workerFinished(ticket) }, requiredSession)
            withContext(Dispatchers.IO) { UploadJournal.write(app, entry.copy(phase = "confirmed")) }
            CloudUploadState.Confirmed(recording.id, entry.projectId, requiredSession != null).also { _state.value = it }
        } catch (error: CancellationException) {
            gate.stopProgress(ticket)
            _state.value = CloudUploadState.Failed(recording.id, projectId, "uncertain", "Upload abgebrochen oder unterbrochen; die lokale Aufnahme bleibt erhalten.")
            entry?.let { withContext(NonCancellable + Dispatchers.IO) { runCatching { UploadJournal.write(app, it.copy(phase = "uncertain")) } } }
            throw error
        } catch (error: Exception) {
            gate.stopProgress(ticket)
            val kind = (error as? UploadException)?.kind ?: "uncertain"
            val phase = when (kind) { "project_missing" -> "project_missing"; "conflict", "manifest_mismatch", "manifest_partial" -> "conflict"; else -> "uncertain" }
            entry?.let { withContext(Dispatchers.IO) { runCatching { UploadJournal.write(app, it.copy(phase = phase)) } } }
            CloudUploadState.Failed(recording.id, projectId, kind, error.message?.take(220) ?: "Upload nicht bestätigt; die lokale Aufnahme bleibt erhalten.").also { _state.value = it }
        } finally { gate.operationFinished(ticket) }
    }
}
