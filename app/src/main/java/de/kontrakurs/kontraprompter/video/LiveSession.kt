package de.kontrakurs.kontraprompter.video

import android.content.Context
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import de.kontrakurs.kontraprompter.network.LanSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.UUID

sealed interface LocalRecording {
    data object Idle : LocalRecording
    data class Preparing(val id: String) : LocalRecording
    data class Recording(val id: String) : LocalRecording
    data class Finalizing(val id: String) : LocalRecording
    data class Ready(val id: String, val file: File, val bytes: Long, val durationUs: Long, val reason: String) : LocalRecording
    data class Partial(val id: String, val file: File, val bytes: Long, val durationUs: Long, val message: String) : LocalRecording
    data class Failed(val message: String, val blocked: Boolean = false, val id: String? = null) : LocalRecording
}

fun LocalRecording.isActive(): Boolean = this is LocalRecording.Preparing || this is LocalRecording.Recording || this is LocalRecording.Finalizing || (this is LocalRecording.Failed && blocked)
internal fun shouldReleaseVideo(recording: LocalRecording, closed: Boolean, disconnected: Boolean): Boolean =
    !recording.isActive() && (closed || disconnected)
internal fun releaseBlockedRecording(state: LocalRecording, id: String): LocalRecording =
    if (state is LocalRecording.Failed && state.blocked && state.id == id) state.copy(blocked = false) else state

class LiveSession(context: Context, val host: Boolean) : AutoCloseable {
    private val app = context.applicationContext
    private val guard = Any()
    private var closed = false
    private var disconnected = false
    private var stopWhenStarted = false
    private var pendingCameraFailure: String? = null
    private var remoteExclusive = false
    private var recorder: RtcMp4Recorder? = null
    val lan = LanSession(context)
    private val _video = MutableStateFlow<RtcVideo?>(null)
    val video: StateFlow<RtcVideo?> = _video
    private val _recording = MutableStateFlow<LocalRecording>(LocalRecording.Idle)
    val recording: StateFlow<LocalRecording> = _recording

    init {
        lan.onReady = {
            val created = synchronized(guard) {
                if (closed || _video.value != null) null
                else {
                    val lease = if (host) RtcVideo.tryAcquireCamera() else null
                    if (host && lease == null) { lan.report("Kamera noch beschäftigt", "Die vorherige Aufnahme wird noch abgeschlossen. Bitte erneut verbinden."); null }
                    else try { RtcVideo(app, host, lan, lease).also { it.onCameraFailure = ::cameraFailed; _video.value = it; disconnected = false } }
                    catch (error: Exception) { lease?.let { RtcVideo.releaseCamera(it) }; lan.report("Videofehler", error.localizedMessage ?: "WebRTC nicht verfügbar"); null }
                }
            }
            created?.start()
        }
        lan.onMessage = { type, payload -> _video.value?.message(type, payload) }
        lan.onDisconnect = {
            val release = synchronized(guard) {
                disconnected = true
                _video.value?.stopAnyBrowserPreview()
                if (shouldReleaseVideo(_recording.value, closed, disconnected)) _video.value.also { _video.value = null } else null
            }
            release?.close()
        }
        if (host) lan.startHost() else lan.startDiscovery()
    }

    fun canStartRemoteRecording(): Boolean = synchronized(guard) {
        host && !closed && !disconnected && _video.value != null && !_recording.value.isActive() &&
            ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    fun setRemoteExclusive(enabled: Boolean) = synchronized(guard) {
        remoteExclusive = enabled
        if (!enabled) _video.value?.stopAnyBrowserPreview()
    }

    internal fun browserPreviewAvailable(): Boolean = synchronized(guard) {
        host && remoteExclusive && !closed && !disconnected && _video.value?.browserPreviewAvailable() == true
    }

    internal fun startBrowserPreview(id: String, offer: String, allowed: () -> Boolean, emit: (String, String, String?, Int?) -> Unit) {
        val current = synchronized(guard) { if (browserPreviewAvailable()) _video.value else null }
        if (current == null) emit("end", "", null, null)
        else current.startBrowserPreview(id, offer, { allowed() && browserPreviewAvailable() && _video.value === current }, emit)
    }
    internal fun browserPreviewCandidate(id: String, data: String, mid: String?, line: Int?) {
        synchronized(guard) { _video.value }?.browserPreviewCandidate(id, data, mid, line)
    }
    internal fun stopBrowserPreview(id: String) { synchronized(guard) { _video.value }?.stopBrowserPreview(id) }

    fun startRecording(id: String = UUID.randomUUID().toString(), projectId: String? = null): Boolean {
        val current: RtcVideo
        if (runCatching { UUID.fromString(id).toString() != id || (projectId != null && UUID.fromString(projectId).toString() != projectId) }.getOrDefault(true)) return false
        synchronized(guard) {
            if (!host || closed || disconnected || (remoteExclusive && projectId == null) || _recording.value.isActive() || (_recording.value as? LocalRecording.Failed)?.blocked == true) return false
            current = _video.value ?: return false
            stopWhenStarted = false
            pendingCameraFailure = null
            _recording.value = LocalRecording.Preparing(id)
        }
        val submitted = current.withLocalTrack { track, egl ->
            if (track == null) {
                recordingEvent(RtcMp4Recorder.Event.Failed(id, "Kamera ist noch nicht bereit", null))
            } else {
                val capture = RtcMp4Recorder(app, track, egl, id, projectId, ::recordingEvent)
                val started = synchronized(guard) {
                    recorder = capture
                    val ok = capture.start()
                    if (ok) {
                        pendingCameraFailure?.let { capture.abort(it) }
                        if (stopWhenStarted || closed) capture.stop()
                    }
                    ok
                }
                if (!started) recordingEvent(RtcMp4Recorder.Event.Failed(id, "Vorheriger Recorder ist noch nicht freigegeben", null))
            }
        }
        if (!submitted) recordingEvent(RtcMp4Recorder.Event.Failed(id, "Kamera ist nicht verfügbar", null))
        return submitted
    }

    private fun cameraFailed(message: String) = synchronized(guard) {
        if (_recording.value.isActive()) {
            pendingCameraFailure = message
            recorder?.abort(message)
        }
    }

    fun stopRecording(): Boolean = stopRecording(null)

    fun stopRecording(recordingId: String?): Boolean = synchronized(guard) {
        val activeId = when (val state = _recording.value) {
            is LocalRecording.Preparing -> state.id
            is LocalRecording.Recording -> state.id
            is LocalRecording.Finalizing -> state.id
            else -> null
        }
        if (recordingId != null && activeId != recordingId) return false
        if (!_recording.value.isActive() || _recording.value is LocalRecording.Finalizing || _recording.value is LocalRecording.Failed) return false
        stopWhenStarted = true
        recorder?.stop()
        true
    }

    private fun recordingEvent(event: RtcMp4Recorder.Event) {
        var release: RtcVideo? = null
        synchronized(guard) {
            val activeId = when (val state = _recording.value) {
                is LocalRecording.Preparing -> state.id
                is LocalRecording.Recording -> state.id
                is LocalRecording.Finalizing -> state.id
                is LocalRecording.Failed -> if (state.blocked) state.id else null
                else -> null
            }
            val eventId = when (event) {
                is RtcMp4Recorder.Event.Preparing -> event.recordingId
                is RtcMp4Recorder.Event.Recording -> event.recordingId
                is RtcMp4Recorder.Event.Finalizing -> event.recordingId
                is RtcMp4Recorder.Event.Ready -> event.recordingId
                is RtcMp4Recorder.Event.Partial -> event.recordingId
                is RtcMp4Recorder.Event.Failed -> event.recordingId
                is RtcMp4Recorder.Event.ResourcesReleased -> event.recordingId
            }
            if (eventId != activeId) return
            when (event) {
                is RtcMp4Recorder.Event.Preparing -> Unit
                is RtcMp4Recorder.Event.Recording -> _recording.value = LocalRecording.Recording(event.recordingId)
                is RtcMp4Recorder.Event.Finalizing -> _recording.value = LocalRecording.Finalizing(event.recordingId)
                is RtcMp4Recorder.Event.Ready -> {
                    _recording.value = LocalRecording.Ready(event.recordingId, event.file, event.bytes, event.durationUs, event.reason.name)
                    recorder = null
                }
                is RtcMp4Recorder.Event.Partial -> {
                    _recording.value = LocalRecording.Partial(event.recordingId, event.file, event.bytes, event.durationUs, event.message)
                    recorder = null
                }
                is RtcMp4Recorder.Event.Failed -> {
                    _recording.value = LocalRecording.Failed(event.message, event.blocked, event.recordingId)
                    recorder = null
                }
                is RtcMp4Recorder.Event.ResourcesReleased -> {
                    _recording.value = releaseBlockedRecording(_recording.value, event.recordingId)
                }
            }
            if (shouldReleaseVideo(_recording.value, closed, disconnected)) {
                release = _video.value
                _video.value = null
            }
        }
        release?.close()
    }

    override fun close() {
        val release: RtcVideo?
        synchronized(guard) {
            if (closed) return
            closed = true
            _video.value?.stopAnyBrowserPreview()
            stopWhenStarted = true
            if (shouldReleaseVideo(_recording.value, closed, disconnected)) { release = _video.value; _video.value = null }
            else { recorder?.stop(); release = null }
        }
        lan.close()
        release?.close()
    }
}
