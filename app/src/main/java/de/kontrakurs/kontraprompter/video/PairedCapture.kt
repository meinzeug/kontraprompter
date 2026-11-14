package de.kontrakurs.kontraprompter.video

import de.kontrakurs.kontraprompter.network.LanSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

internal data class PairedCaptureState(
    val phase: String = "unknown", val recordingId: String? = null,
    val pending: Boolean = false, val message: String? = null,
)

/** Commands are admitted only by the already-authenticated and approved LAN session. */
internal class PairedCapture(
    private val host: Boolean,
    private val lan: LanSession,
    private val scope: CoroutineScope,
    private val session: LiveSession,
) {
    private val lock = Any()
    private val statusLock = Any()
    private val revision = AtomicLong()
    private var lastRevision = -1L
    private var pendingId: String? = null
    private var timeout: Job? = null
    private val handled = PairedCommandLedger()
    private val _state = MutableStateFlow(PairedCaptureState())
    val state: StateFlow<PairedCaptureState> = _state

    fun ready() {
        if (host) sendStatus() else {
            lan.send("capture_query", "")
            scope.launch {
                delay(3_000)
                synchronized(lock) {
                    if (lan.state.value.ready && _state.value.phase == "unknown")
                        _state.value = _state.value.copy(message = "Kamerastatus nicht verfügbar. Bitte beide Apps aktualisieren.")
                }
            }
        }
    }

    fun triggerUnavailable() = synchronized(lock) {
        _state.value = _state.value.copy(message = "Kamera ist gerade nicht bereit. Bitte Status prüfen.")
    }

    fun disconnected() = synchronized(lock) {
        pendingId = null; timeout?.cancel(); timeout = null; lastRevision = -1L
        _state.value = PairedCaptureState(message = "Kameraverbindung getrennt. Aufnahmezustand unbekannt.")
    }

    fun changed() { if (host && lan.state.value.ready) sendStatus() }

    fun toggle(projectId: String? = null): Boolean {
        val phase = state.value.phase
        return when (phase) {
            "idle", "ready", "partial", "failed" -> request("start", projectId)
            "recording" -> request("pause")
            "paused" -> request("resume")
            else -> false
        }
    }

    fun request(action: String, projectId: String? = null): Boolean = synchronized(lock) {
        if (host || !lan.state.value.ready || pendingId != null) return false
        val snapshot = _state.value
        if (!pairedActionAllowed(action, snapshot.phase, pending = false)) return false
        val requestId = UUID.randomUUID().toString()
        val recordingId = if (action == "start") UUID.randomUUID().toString() else snapshot.recordingId ?: return false
        val project = if (action == "start" && projectId != null) canonicalUuid(projectId) ?: return false else null
        val message = JSONObject().put("request", requestId).put("action", action).put("recording", recordingId).apply { if (project != null) put("project", project) }.toString()
        pendingId = requestId
        _state.value = snapshot.copy(pending = true, message = null)
        if (!lan.send("capture_command", message)) {
            pendingId = null; _state.value = snapshot.copy(message = "Kameraverbindung nicht verfügbar."); return false
        }
        timeout = scope.launch {
            delay(8_000)
            synchronized(lock) {
                if (pendingId == requestId) {
                    pendingId = null
                    _state.value = _state.value.copy(pending = false, message = "Keine Bestätigung. Bitte Kamerastatus prüfen; der Befehl wird nicht automatisch wiederholt.")
                    lan.send("capture_query", "")
                }
            }
        }
        true
    }

    fun message(type: String, payload: String): Boolean {
        if (type !in setOf("capture_query", "capture_command", "capture_status", "capture_ack")) return false
        if (!lan.state.value.ready) return true
        if (host && type == "capture_query") { sendStatus(); return true }
        val data = runCatching { JSONObject(payload) }.getOrNull() ?: return true
        if (host && type == "capture_command") {
            val request = canonicalUuid(data.optString("request")) ?: return true
            val recording = canonicalUuid(data.optString("recording")) ?: return true
            val action = data.optString("action")
            val rawProject = data.optString("project", "")
            val project = if (rawProject.isEmpty()) null else canonicalUuid(rawProject) ?: return true
            if (action != "start" && project != null) return true
            val accepted = handled.execute(request, PairedCommand(action, recording, project)) {
                when (action) {
                    "start" -> session.startRecording(recording, project, if (project == null) RecordingOrigin.LAN_UNASSIGNED else RecordingOrigin.LAN)
                    "pause" -> session.pauseRecording(recording)
                    "resume" -> session.resumeRecording(recording)
                    "stop" -> session.stopRecording(recording)
                    else -> false
                }
            }
            lan.send("capture_ack", JSONObject().put("request", request).put("accepted", accepted).toString())
            sendStatus()
            return true
        }
        if (!host && type == "capture_ack") {
            val request = canonicalUuid(data.optString("request")) ?: return true
            synchronized(lock) {
                if (pendingId == request) {
                    pendingId = null; timeout?.cancel(); timeout = null
                    _state.value = _state.value.copy(pending = false, message = if (data.optBoolean("accepted", false)) null else "Befehl wurde von der Kamera nicht angenommen.")
                }
            }
            return true
        }
        if (!host && type == "capture_status") {
            val rev = data.optLong("revision", -1)
            val phase = data.optString("phase")
            if (rev < 0 || phase !in setOf("idle", "preparing", "recording", "paused", "finalizing", "ready", "partial", "failed")) return true
            val rawId = data.optString("recording", "")
            val id = if (rawId.isEmpty()) null else canonicalUuid(rawId) ?: return true
            if (phase in setOf("preparing", "recording", "paused", "finalizing") && id == null) return true
            synchronized(lock) {
                if (rev > lastRevision) {
                    lastRevision = rev
                    val failure = if (phase == "failed") data.optString("message").take(240).takeIf { it.isNotBlank() } else null
                    _state.value = _state.value.copy(phase = phase, recordingId = id, message = failure ?: _state.value.message)
                }
            }
        }
        return true
    }

    private fun sendStatus() {
      val payload = synchronized(statusLock) {
        val recording = session.recording.value
        val (phase, id) = when (recording) {
            LocalRecording.Idle -> "idle" to null
            is LocalRecording.Preparing -> "preparing" to recording.id
            is LocalRecording.Recording -> "recording" to recording.id
            is LocalRecording.Paused -> "paused" to recording.id
            is LocalRecording.Finalizing -> "finalizing" to recording.id
            is LocalRecording.Ready -> "ready" to recording.id
            is LocalRecording.Partial -> "partial" to recording.id
            is LocalRecording.Failed -> "failed" to recording.id
        }
        val data = JSONObject().put("revision", revision.incrementAndGet()).put("phase", phase)
        if (id != null) data.put("recording", id)
        if (recording is LocalRecording.Failed) data.put("message", recording.message.take(240))
        data.toString()
      }
      lan.send("capture_status", payload)
    }

    private fun canonicalUuid(value: String): String? = runCatching { UUID.fromString(value).toString().takeIf { it == value } }.getOrNull()
}
