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
data class CameraSelection(val front: Boolean? = null, val canSwitch: Boolean = false, val switching: Boolean = false, val error: String? = null)
class LiveSession {
    val recording = MutableStateFlow<LocalRecording>(LocalRecording.Idle)
    val cameraSelection = MutableStateFlow(CameraSelection())
    fun startPairedRecording(marker: Any, id: String, project: String?) = false
    fun pausePairedRecording(marker: Any, id: String) = false
    fun resumePairedRecording(marker: Any, id: String) = false
    fun stopPairedRecording(marker: Any, id: String) = false
    fun requestPairedCameraSwitch(marker: Any, front: Boolean) = false
}
