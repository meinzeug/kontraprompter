package de.kontrakurs.kontraprompter.video

import de.kontrakurs.kontraprompter.network.LanSession
import android.os.SystemClock
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
    private var timeout: Job? = null
    private val statusProbe = PairedStatusProbe()
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
        statusProbe.reset()
        pendingId = null; pendingAction = null; timeout?.cancel(); timeout = null; lastRevision = -1L
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
        pendingId = requestId; pendingAction = action
        _state.value = snapshot.copy(pending = true, message = null)
        val sent = if (binding == null) lan.send("capture_command", message)
            else lan.sendOnApprovedConnection(binding.connection, "capture_command", message)
        if (!sent) {
            pendingId = null; pendingAction = null; _state.value = snapshot.copy(message = "Kameraverbindung nicht verfügbar."); return false
        }
        timeout = scope.launch {
            delay(8_000)
            synchronized(lock) {
                if (pendingId == requestId) {
                    pendingId = null; pendingAction = null
                    _state.value = _state.value.copy(pending = false, message = "Keine Bestätigung. Bitte Kamerastatus prüfen; der Befehl wird nicht automatisch wiederholt.")
                    lan.send("capture_query", "")
                }
            }
        }
        true
    }

    /** Explicit, opt-in observation only. Not connected to the web dashboard. */
    fun requestReadOnlyStatus(): Boolean {
        if (host) return false
        val marker = lan.approvedConnectionMarker() ?: return false
        val request = UUID.randomUUID().toString()
        val started = lan.admitOnApprovedConnection(marker) { statusProbe.begin(marker, request, SystemClock.elapsedRealtime()) } == true
        if (!started) return false
        if (!lan.sendOnApprovedConnection(marker, "camera_status_probe_v1", JSONObject().put("request", request).toString())) {
            statusProbe.cancel(marker, request)
            return false
        }
        return true
    }

    /** The result can become stale immediately and must never authorize a command. */
    fun readOnlyStatus(): PairedStatusObservation? {
        val marker = lan.approvedConnectionMarker() ?: return null
        return lan.admitOnApprovedConnection(marker) { statusProbe.result(marker, SystemClock.elapsedRealtime()) }
    }

    fun message(marker: Any, type: String, payload: String): Boolean {
        if (type !in setOf("capture_query", "capture_command", "capture_status", "capture_ack", "camera_status_probe_v1", "camera_status_reply_v1")) return false
        if (!lan.isApprovedConnection(marker)) return true
        if (type == "camera_status_probe_v1") {
            if (!host || payload.length > 256) return true
            val request = runCatching { canonicalUuid(JSONObject(payload).optString("request")) }.getOrNull() ?: return true
            val snapshot = session.pairedStatusSnapshot(marker) ?: return true
            val reply = JSONObject().put("request", request).put("revision", snapshot.revision).put("phase", snapshot.phase)
                .put("projectKnown", snapshot.projectKnown)
                .put("cameraFacing", when (snapshot.cameraFront) { true -> "front"; false -> "back"; null -> "unknown" })
            snapshot.recordingId?.let { reply.put("recording", it) }
            snapshot.projectId?.let { reply.put("project", it) }
            lan.sendOnApprovedConnection(marker, "camera_status_reply_v1", reply.toString())
            return true
        }
        if (type == "camera_status_reply_v1") {
            if (host || payload.length > 1024) return true
            val data = runCatching { JSONObject(payload) }.getOrNull() ?: return true
            val request = canonicalUuid(data.optString("request")) ?: return true
            val rev = data.opt("revision") as? Number ?: return true
            val revision = rev.toLong()
            if (revision < 1 || rev.toDouble() != revision.toDouble()) return true
            val phase = data.optString("phase")
            if (phase !in setOf("idle", "preparing", "recording", "paused", "finalizing", "ready", "partial", "failed")) return true
            val recording = if (data.has("recording")) canonicalUuid(data.optString("recording")) ?: return true else null
            if ((phase in setOf("preparing", "recording", "paused", "finalizing", "ready", "partial") && recording == null) || (phase == "idle" && recording != null)) return true
            val known = data.opt("projectKnown") as? Boolean ?: return true
            val project = if (data.has("project")) canonicalUuid(data.optString("project")) ?: return true else null
            if ((!known && project != null) || (known && recording == null)) return true
            val front = when (data.optString("cameraFacing")) { "front" -> true; "back" -> false; "unknown" -> null; else -> return true }
            val value = PairedStatusObservation(revision, phase, recording, known, project, front)
            lan.admitOnApprovedConnection(marker) { statusProbe.receive(marker, request, SystemClock.elapsedRealtime(), value) }
            return true
        }
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
                    val accepted = data.optBoolean("accepted", false)
                    if (cameraAckNeedsStatus(pendingAction, accepted)) {
                        // Admission is not a completed switch. Query an authoritative snapshot tied
                        // to this request; status may arrive before or after the acknowledgement.
                        lan.send("capture_query", JSONObject().put("request", request).toString())
                    } else {
                        pendingId = null; pendingAction = null; timeout?.cancel(); timeout = null
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
                if (isNewCaptureStatus(rev, lastRevision)) {
                    lastRevision = rev
                    val failure = if (phase == "failed") data.optString("message").take(240).takeIf { it.isNotBlank() } else null
                    val facing = when (data.optString("cameraFacing")) { "front" -> true; "back" -> false; else -> null }
                    val cameraError = data.optString("cameraError").take(200).takeIf { it.isNotBlank() }
                    _state.value = _state.value.copy(phase = phase, recordingId = id, message = failure ?: _state.value.message, cameraFront = facing, cameraCanSwitch = data.optBoolean("cameraCanSwitch", false), cameraSwitching = data.optBoolean("cameraSwitching", false), cameraError = cameraError)
                }
                val query = canonicalUuid(data.optString("query"))
                if (cameraStatusAnswersPending(pendingAction, pendingId, query)) {
                    pendingId = null; pendingAction = null; timeout?.cancel(); timeout = null
                    _state.value = _state.value.copy(pending = false)
                }
            }
        }
        return true
    }

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
