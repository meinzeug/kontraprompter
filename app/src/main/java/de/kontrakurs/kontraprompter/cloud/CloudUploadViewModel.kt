package de.kontrakurs.kontraprompter.cloud

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope

/** Retained across configuration changes without retaining an Activity. */
internal class CloudUploadViewModel(application: Application) : AndroidViewModel(application) {
    val client = StudioClient()
    val coordinator = CloudUploadCoordinator(application, client)
    val lanAutoUploader = LanAutoUploader(application, client, coordinator)
    init { lanAutoUploader.start(viewModelScope) }
}
