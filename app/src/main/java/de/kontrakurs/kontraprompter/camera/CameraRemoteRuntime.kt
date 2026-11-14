package de.kontrakurs.kontraprompter.camera

import android.content.Context
import android.os.SystemClock
import de.kontrakurs.kontraprompter.cloud.CameraDeviceTicketException
import de.kontrakurs.kontraprompter.cloud.CameraRegistrationRequiredException
import de.kontrakurs.kontraprompter.cloud.CloudUploadCoordinator
import de.kontrakurs.kontraprompter.cloud.CloudUploadState
import de.kontrakurs.kontraprompter.cloud.StudioClient
import de.kontrakurs.kontraprompter.cloud.UploadJournal
import de.kontrakurs.kontraprompter.video.LiveSession
import de.kontrakurs.kontraprompter.video.savedRecordings
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The UI stays disabled until the v2 fixture and real-device tests have passed. */
internal const val CAMERA_REMOTE_UI_ENABLED = false

internal class CameraRemoteRuntime(context: Context, private val session: LiveSession) {
    private val app = context.applicationContext
    private val client = StudioClient()
    private val _status = MutableStateFlow("Aus")
    val status: StateFlow<String> = _status
    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active
    private val _recoveryRequired = MutableStateFlow(false)
    val recoveryRequired: StateFlow<Boolean> = _recoveryRequired
    private val _uploadStatus = MutableStateFlow("")
    val uploadStatus: StateFlow<String> = _uploadStatus
    // Last defense: a child failure must never crash the Android process.
    private val handler = CoroutineExceptionHandler { _, _ -> _status.value = "Websteuerung gestört. Lokale Aufnahmen bleiben erhalten." }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + handler)
    private var job: Job? = null
    private var uploadJob: Job? = null
    private val uploads = CloudUploadCoordinator(app, client)
    private var nextUploadCheck = 0L
    @Volatile private var previewTicket: StudioClient.CameraDeviceTicket? = null
    private val preview = CameraPreviewRuntime(session, client) { previewTicket }
    val previewEnabled: StateFlow<Boolean> = preview.enabled
    val previewStatus: StateFlow<String> = preview.status

    fun activate(password: String) {
        if (job?.isActive == true) return
        _active.value = true
        job = scope.launch {
            var controller: RemoteCameraController? = null
            try {
                _status.value = "Kamera wird mit Videomaker verbunden …"
                client.login(password)
                val identity = withContext(Dispatchers.IO) { CameraDeviceIdentity.loadOrCreate(app) }
                var ticket: StudioClient.CameraDeviceTicket? = try { client.startCameraDeviceSession(identity) }
                catch (_: CameraRegistrationRequiredException) {
                    // This branch runs only following the user's explicit activation.
                    client.registerCameraDevice(identity)
                    client.startCameraDeviceSession(identity)
                }
                val journal = withContext(Dispatchers.IO) { RemoteCameraJournal.open(app, identity.id) }
                controller = RemoteCameraController.open(journal, session)
                // A reset can occur while an older login/challenge is in flight. After acquiring
                // the process owner, recheck before sending any poll or executing any command.
                val currentIdentity = withContext(Dispatchers.IO) { CameraDeviceIdentity.loadOrCreate(app) }
                check(currentIdentity.id == identity.id && currentIdentity.publicKeyDer.contentEquals(identity.publicKeyDer)) { "Kameraidentität hat sich geändert. Bitte erneut verbinden." }
                while (isActive) {
                    try {
                        val currentTicket = ticket ?: client.startCameraDeviceSession(identity).also { ticket = it }
                        previewTicket = currentTicket
                        controller.poll { client.pollCameraDevice(currentTicket, it) }
                        _status.value = "Websteuerung verbunden"
                        scheduleUpload(journal)
                        delay(1000)
                    } catch (_: CameraDeviceTicketException) {
                        _status.value = "Kamerasitzung wird erneuert …"
                        previewTicket = null
                        preview.disable("Webvorschau aus · Kamerasitzung wird erneuert")
                        ticket = null
                    } catch (_: CameraRegistrationRequiredException) {
                        _status.value = "Kamera muss erneut registriert werden · Aufnahme läuft gegebenenfalls lokal weiter"
                        delay(30_000)
                    } catch (_: CameraReconciliationPendingException) {
                        _status.value = "Lokale Aufnahme wird erneut geprüft · Original bleibt erhalten"
                        delay(5000)
                    } catch (_: IOException) {
                        _status.value = "Verbindung unterbrochen · Aufnahme läuft gegebenenfalls lokal weiter"
                        delay(2000)
                    }
                }
            } catch (error: CancellationException) { throw error }
            catch (error: CameraIdentityRecoveryRequiredException) { _recoveryRequired.value = true; _status.value = error.message ?: "Kameraidentität nicht verfügbar." }
            catch (error: Exception) { _status.value = error.message?.take(220) ?: "Websteuerung konnte nicht aktiviert werden." }
            finally {
                withContext(NonCancellable) {
                    previewTicket = null
                    preview.disable()
                    uploadJob?.cancel()
                    val failure = runCatching { controller?.close() }.exceptionOrNull()
                    if (failure != null) {
                        session.stopRecording()
                        _status.value = "Websteuerung konnte nicht sicher beendet werden. Aufnahme bleibt lokal; Kamera gesperrt."
                    } else if (_status.value == "Websteuerung verbunden" || _status.value.startsWith("Verbindung unterbrochen") || _status.value == "Wird getrennt …") _status.value = "Aus"
                    _active.value = false
                }
            }
        }
    }

    private fun scheduleUpload(journal: RemoteCameraJournal) {
        if (uploadJob?.isActive == true || SystemClock.elapsedRealtime() < nextUploadCheck) return
        nextUploadCheck = SystemClock.elapsedRealtime() + 30_000
        uploadJob = scope.launch {
            try {
            val candidate = withContext(Dispatchers.IO) {
                val captures = try { journal.snapshot().captures.filter(::cameraUploadEligible).asReversed() }
                    catch (error: Exception) { session.stopRecording(); throw error }
                val files = savedRecordings(app).associateBy { it.id }
                captures.firstNotNullOfOrNull { capture ->
                    val file = files[capture.recordingId] ?: return@firstNotNullOfOrNull null
                    val project = capture.projectId ?: return@firstNotNullOfOrNull null
                    if (file.partial || file.projectId != project) return@firstNotNullOfOrNull null
                    val phase = UploadJournal.get(app, file, project)?.phase
                    if (phase in setOf("confirmed", "project_missing", "conflict")) null else file to project
                }
            }
            if (candidate != null) {
                _uploadStatus.value = "Video wird zu Videomaker übertragen …"
                uploads.upload(candidate.first, candidate.second)
                _uploadStatus.value = when (val state = uploads.state.value) {
                    is CloudUploadState.Confirmed -> "Video für das Skript bestätigt · Original bleibt lokal"
                    is CloudUploadState.Failed -> {
                        if (state.kind == "manifest_pending") {
                            try { withContext(Dispatchers.IO) { journal.retryReport(candidate.first.id, "ready") } }
                            catch (error: Exception) { session.stopRecording(); throw error }
                        }
                        state.message
                    }
                    else -> "Upload nicht bestätigt · Original bleibt lokal"
                }
            }
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { _uploadStatus.value = "Uploadstatus nicht lesbar. Das Original bleibt lokal." }
        }
    }

    fun deactivate() { _status.value = "Wird getrennt …"; job?.cancel() }
    fun enablePreview() { if (_active.value && previewTicket != null) preview.enable() }
    fun disablePreview() { preview.disable() }
    fun createNewIdentityAfterConfirmation() {
        if (_active.value || job?.isActive == true) return
        _active.value = true
        job = scope.launch {
            try {
                _status.value = "Neue Kameraidentität wird vorbereitet …"
                withContext(Dispatchers.IO) { CameraDeviceIdentity.createNewAfterConfirmation(app) }
                _recoveryRequired.value = false
                _status.value = "Neue Identität vorbereitet. Alte Aufnahmen bleiben unter ‚Aufnahmen‘ exportierbar; alte Aufträge müssen separat geklärt werden."
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { _recoveryRequired.value = true; _status.value = error.message?.take(220) ?: "Neue Kameraidentität konnte nicht erstellt werden." }
            finally { _active.value = false }
        }
    }
    fun dispose() { preview.close(); scope.cancel() }
}
