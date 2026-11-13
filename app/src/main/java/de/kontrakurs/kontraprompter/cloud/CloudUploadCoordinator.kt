package de.kontrakurs.kontraprompter.cloud

import android.content.Context
import de.kontrakurs.kontraprompter.video.SavedRecording
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

internal sealed interface CloudUploadState {
    data object Idle : CloudUploadState
    data class Running(val sent: Long, val total: Long) : CloudUploadState
    data class Confirmed(val projectId: String) : CloudUploadState
    data class Failed(val message: String) : CloudUploadState
}

/** One local upload at a time; the worker owns its slot until its socket is closed. */
internal class CloudUploadCoordinator(context: Context, private val client: StudioClient) {
    private val app = context.applicationContext
    private val busy = AtomicBoolean(false)
    private val _state = MutableStateFlow<CloudUploadState>(CloudUploadState.Idle)
    val state: StateFlow<CloudUploadState> = _state

    suspend fun upload(recording: SavedRecording, projectId: String) {
        if (!busy.compareAndSet(false, true)) { _state.value = CloudUploadState.Failed("Ein vorheriger Upload wird noch beendet."); return }
        var entry: UploadEntry? = null
        var workerStarted = false
        val workerDone = AtomicBoolean(false)
        val operationDone = AtomicBoolean(false)
        fun releaseWhenDone() { if (workerDone.get() && operationDone.get()) busy.set(false) }
        try {
            _state.value = CloudUploadState.Running(0, recording.bytes)
            entry = withContext(Dispatchers.IO) { UploadJournal.begin(app, recording, projectId) }
            workerStarted = true
            client.uploadRecording(recording, entry, { sent, total -> _state.value = CloudUploadState.Running(sent, total) }, { workerDone.set(true); releaseWhenDone() })
            withContext(Dispatchers.IO) { UploadJournal.write(app, entry.copy(phase = "confirmed")) }
            _state.value = CloudUploadState.Confirmed(entry.projectId)
        } catch (error: CancellationException) {
            entry?.let { withContext(NonCancellable + Dispatchers.IO) { runCatching { UploadJournal.write(app, it.copy(phase = "uncertain")) } } }
            _state.value = CloudUploadState.Failed("Upload abgebrochen oder unterbrochen; die lokale Aufnahme bleibt erhalten.")
            throw error
        } catch (error: Exception) {
            val phase = (error as? UploadException)?.kind?.takeIf { it == "project_missing" || it == "conflict" } ?: "uncertain"
            entry?.let { withContext(Dispatchers.IO) { runCatching { UploadJournal.write(app, it.copy(phase = phase)) } } }
            _state.value = CloudUploadState.Failed(error.message?.take(220) ?: "Upload nicht bestätigt; die lokale Aufnahme bleibt erhalten.")
        } finally { if (!workerStarted) workerDone.set(true); operationDone.set(true); releaseWhenDone() }
    }
}
