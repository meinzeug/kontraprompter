package de.kontrakurs.kontraprompter.cloud

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.kontrakurs.kontraprompter.video.LiveSession
import de.kontrakurs.kontraprompter.video.LocalRecording
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow

/** Must remain false until server receipt, isolated HTTP/crash and device tests pass. */
internal const val EXPERIMENTAL_LIVE_UPLOAD = false
internal fun recorderSnapshot(state: LocalRecording?): LiveRecorderSnapshot = when (state) {
    is LocalRecording.Recording -> LiveRecorderSnapshot(state.id, true)
    is LocalRecording.Paused -> LiveRecorderSnapshot(state.id, true)
    else -> LiveRecorderSnapshot(null, false)
}

/** Retained across configuration changes without retaining an Activity. */
internal class CloudUploadViewModel(application: Application) : AndroidViewModel(application) {
    val client = StudioClient()
    val coordinator = CloudUploadCoordinator(application, client)
    val lanAutoUploader = LanAutoUploader(application, client, coordinator)
    val liveUploader = LiveCameraUploadSupervisor(application, client, coordinator)
    init { lanAutoUploader.start(viewModelScope) }

    /** The source is the active camera session; an on-disk manifest cannot assert liveness. */
    suspend fun observeLiveCamera(session: LiveSession?) {
        if (!EXPERIMENTAL_LIVE_UPLOAD) return
        coroutineScope {
            val snapshot = MutableStateFlow(recorderSnapshot(null))
            val worker = liveUploader.start(this, snapshot)
            try {
                if (session == null || !session.host) awaitCancellation()
                else session.recording.collect { snapshot.value = recorderSnapshot(it) }
            } finally { worker.cancelAndJoin() }
        }
    }
}
