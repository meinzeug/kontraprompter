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
    val cameraFront: Boolean? = null, val cameraCanSwitch: Boolean = false,
    val cameraSwitching: Boolean = false, val cameraError: String? = null,
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
    private var pendingAction: String? = null
    private var statusQueryAttempts = 0
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
        pendingId = null; pendingAction = null; statusQueryAttempts = 0; timeout?.cancel(); timeout = null; lastRevision = -1L
        _state.value = PairedCaptureState(message = PAIRED_DISCONNECTED_MESSAGE)
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

    fun request(action: String, projectId: String? = null): Boolean = requestBound(action, projectId, null)

    private data class ShutterBinding(val phase: String, val recordingId: String?, val connection: Any)

    /** Recheck take, phase and exact connection while admitting the explicit command. */
    fun requestShutter(action: String, projectId: String?, phase: String, recordingId: String?, connection: Any): Boolean =
        requestBound(action, projectId, ShutterBinding(phase, recordingId, connection))

    private fun requestBound(action: String, projectId: String?, binding: ShutterBinding?): Boolean = synchronized(lock) {
        if (host || !lan.state.value.ready || pendingId != null) return false
        val snapshot = _state.value
        if (binding != null && !shutterRequestMatches(binding.phase, binding.recordingId, snapshot.phase, snapshot.recordingId, snapshot.pending, lan.isApprovedConnection(binding.connection))) return false
        if (!pairedActionAllowed(action, snapshot.phase, pending = false)) return false
        if (action == "switch_camera" && (!snapshot.cameraCanSwitch || snapshot.cameraSwitching || snapshot.cameraFront == null)) return false
        val requestId = UUID.randomUUID().toString()
        val recordingId = if (action == "start" || action == "switch_camera") UUID.randomUUID().toString() else snapshot.recordingId ?: return false
        val project = if (action == "start" && projectId != null) canonicalUuid(projectId) ?: return false else null
        val message = JSONObject().put("request", requestId).put("action", action).put("recording", recordingId).apply { if (project != null) put("project", project); if (action == "switch_camera") put("cameraFront", snapshot.cameraFront) }.toString()
        pendingId = requestId; pendingAction = action; statusQueryAttempts = 0
        _state.value = snapshot.copy(pending = true, message = null)
        val sent = if (binding == null) lan.send("capture_command", message)
            else lan.sendOnApprovedConnection(binding.connection, "capture_command", message)
        if (!sent) {
            pendingId = null; pendingAction = null; statusQueryAttempts = 0; _state.value = snapshot.copy(message = "Kameraverbindung nicht verfügbar."); return false
        }
        timeout = scope.launch {
            delay(8_000)
            synchronized(lock) {
                if (pendingId == requestId) {
                    // Sending is asynchronous: even a status query after timeout may
                    // overtake an unsent start. Keep that start fenced until its ACK
                    // and a later, correlated status arrive, or the session is reset.
                    val keepPending = retainPendingAfterTimeout(pendingAction)
                    if (!keepPending) { pendingId = null; pendingAction = null; statusQueryAttempts = 0 }
                    _state.value = _state.value.copy(pending = keepPending, message = if (keepPending)
                        START_STATUS_TIMEOUT_MESSAGE
                    else "Keine Bestätigung. Bitte Kamerastatus prüfen; der Befehl wird nicht automatisch wiederholt.")
                    if (keepPending && statusQueryAttempts > 0) {
                        // Only an ACK permits a correlated retry. A query sent before
                        // the ACK could overtake an asynchronously queued start.
                        if (statusQueryAttempts < MAX_STATUS_QUERY_ATTEMPTS) requestPendingStatus(requestId)
                    } else {
                        lan.send("capture_query", "")
                    }
                }
            }
        }
        true
    }

    fun message(marker: Any, type: String, payload: String): Boolean {
        if (type !in setOf("capture_query", "capture_command", "capture_status", "capture_ack")) return false
        if (!lan.isApprovedConnection(marker)) return true
        if (host && type == "capture_query") {
            val queryId = runCatching { canonicalUuid(JSONObject(payload).optString("request")) }.getOrNull()
            sendStatus(queryId); return true
        }
        val data = runCatching { JSONObject(payload) }.getOrNull() ?: return true
        if (host && type == "capture_command") {
            val request = canonicalUuid(data.optString("request")) ?: return true
            val recording = canonicalUuid(data.optString("recording")) ?: return true
            val action = data.optString("action")
            val rawProject = data.optString("project", "")
            val project = if (rawProject.isEmpty()) null else canonicalUuid(rawProject) ?: return true
            if (action != "start" && project != null) return true
            val expectedFront = if (action == "switch_camera") { data.opt("cameraFront") as? Boolean ?: return true } else null
            val accepted = handled.execute(request, PairedCommand(action, recording, project, expectedFront)) {
                when (action) {
                    "start" -> session.startPairedRecording(marker, recording, project)
                    "pause" -> session.pausePairedRecording(marker, recording)
                    "resume" -> session.resumePairedRecording(marker, recording)
                    "stop" -> session.stopPairedRecording(marker, recording)
                    "switch_camera" -> expectedFront != null && session.requestPairedCameraSwitch(marker, expectedFront)
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
                if (isCurrentCaptureAck(pendingId, request)) {
                    // Missing, null or string values are not a negative acknowledgement.
                    val accepted = data.opt("accepted") as? Boolean ?: return true
                    if (cameraAckNeedsStatus(pendingAction, accepted)) {
                        // Admission is not completion. Ask for a snapshot tied to
                        // this request after receiving its acknowledgement.
                        if (pendingAction == "start" && !accepted) _state.value = _state.value.copy(message = "Befehl wurde von der Kamera nicht angenommen.")
                        if (statusQueryAttempts == 0) requestPendingStatus(request)
                    } else {
                        pendingId = null; pendingAction = null; statusQueryAttempts = 0; timeout?.cancel(); timeout = null
                        _state.value = _state.value.copy(pending = false, message = if (accepted) null else "Befehl wurde von der Kamera nicht angenommen.")
                    }
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
                val query = canonicalUuid(data.optString("query"))
                val matchesPending = statusQueryAttempts > 0 && cameraStatusAnswersPending(pendingAction, pendingId, query)
                if (isNewCaptureStatus(rev, lastRevision)) {
                    lastRevision = rev
                    val failure = if (phase == "failed") data.optString("message").take(240).takeIf { it.isNotBlank() } else null
                    val facing = when (data.optString("cameraFacing")) { "front" -> true; "back" -> false; else -> null }
                    val cameraError = data.optString("cameraError").take(200).takeIf { it.isNotBlank() }
                    _state.value = _state.value.copy(phase = phase, recordingId = id, message = pairedMessageAfterStatus(_state.value.message, failure), cameraFront = facing, cameraCanSwitch = data.optBoolean("cameraCanSwitch", false), cameraSwitching = data.optBoolean("cameraSwitching", false), cameraError = cameraError)
                    if (matchesPending) {
                        if (pendingAction == "start") _state.value = _state.value.copy(message = messageAfterConfirmedStartStatus(_state.value.message))
                        pendingId = null; pendingAction = null; statusQueryAttempts = 0; timeout?.cancel(); timeout = null
                        _state.value = _state.value.copy(pending = false)
                    }
                } else if (matchesPending) {
                    // A newer unsolicited status can overtake this query response.
                    // Do not accept the stale snapshot; retry only a bounded number.
                    if (statusQueryAttempts < MAX_STATUS_QUERY_ATTEMPTS) requestPendingStatus(checkNotNull(pendingId))
                    else _state.value = _state.value.copy(message = START_STATUS_STALE_MESSAGE)
                }
            }
        }
        return true
    }

    // Called only while holding lock; a failed send never clears the pending command.
    private fun requestPendingStatus(request: String) {
        statusQueryAttempts++
        if (!lan.send("capture_query", JSONObject().put("request", request).toString())) {
            _state.value = _state.value.copy(message = START_STATUS_SEND_FAILED_MESSAGE)
        }
    }

    private companion object { const val MAX_STATUS_QUERY_ATTEMPTS = 3 }

    private fun sendStatus(queryId: String? = null) {
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
        val camera = session.cameraSelection.value
        val data = JSONObject().put("revision", revision.incrementAndGet()).put("phase", phase)
            .put("cameraFacing", when (camera.front) { true -> "front"; false -> "back"; null -> "unknown" })
            .put("cameraCanSwitch", camera.canSwitch).put("cameraSwitching", camera.switching)
        camera.error?.let { data.put("cameraError", it.take(200)) }
        if (id != null) data.put("recording", id)
        if (queryId != null) data.put("query", queryId)
        if (recording is LocalRecording.Failed) data.put("message", recording.message.take(240))
        data.toString()
      }
      lan.send("capture_status", payload)
    }

    private fun canonicalUuid(value: String): String? = runCatching { UUID.fromString(value).toString().takeIf { it == value } }.getOrNull()
}
