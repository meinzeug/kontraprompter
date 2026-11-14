package de.kontrakurs.kontraprompter.video

import kotlinx.coroutines.flow.MutableStateFlow

sealed class LocalRecording {
    data object Idle : LocalRecording()
    data class Preparing(val id: String) : LocalRecording()
    data class Recording(val id: String) : LocalRecording()
    data class Paused(val id: String) : LocalRecording()
    data class Finalizing(val id: String) : LocalRecording()
    data class Ready(val id: String) : LocalRecording()
    data class Partial(val id: String) : LocalRecording()
    data class Failed(val id: String, val message: String) : LocalRecording()
}
enum class RecordingOrigin { LAN_UNASSIGNED, LAN }
data class CameraSelection(val front: Boolean? = false, val canSwitch: Boolean = true, val switching: Boolean = false, val error: String? = null)
class LiveSession {
    val recording = MutableStateFlow<LocalRecording>(LocalRecording.Idle)
    val cameraSelection = MutableStateFlow(CameraSelection())
    fun startRecording(id: String, project: String?, origin: RecordingOrigin) = true
    fun pauseRecording(id: String) = true
    fun resumeRecording(id: String) = true
    fun stopRecording(id: String) = true
    fun requestCameraSwitch(expectedCurrentFront: Boolean?) = true
}
