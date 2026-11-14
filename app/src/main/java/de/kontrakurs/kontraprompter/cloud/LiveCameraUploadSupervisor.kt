package de.kontrakurs.kontraprompter.cloud

import android.content.Context
import android.util.AtomicFile
import de.kontrakurs.kontraprompter.video.RecordingOrigin
import de.kontrakurs.kontraprompter.video.savedRecordings
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

internal data class LiveCameraStatus(val recordingId: String? = null, val message: String = "", val reauthorizeRequired: Boolean = false)
/** Must be derived from the living session, never from a persisted manifest. */
internal data class LiveRecorderSnapshot(val recordingId: String?, val active: Boolean)
internal fun liveRecorderId(snapshot: LiveRecorderSnapshot): String? = snapshot.recordingId.takeIf { snapshot.active }
/** Do not finalize failed, partial, legacy or differently assigned recordings. */
internal fun mayFinalizeLive(entry: LiveBindingEntry, item: de.kontrakurs.kontraprompter.video.SavedRecording): Boolean =
    item.id == entry.binding.recordingId && !item.partial &&
        ((item.origin == RecordingOrigin.LAN && item.projectId == entry.binding.projectId) ||
         (item.origin == RecordingOrigin.LAN_UNASSIGNED && item.projectId == null))
private data class ActiveOriginal(val id: String, val project: String?, val origin: RecordingOrigin, val file: File)

/**
 * Experimental, explicitly opt-in supervisor. The caller supplies the *living* recorder
 * state; a stale manifest after process death is never evidence of an active capture.
 * This is a foreground queue: process death requires a new camera login and deliberate
 * reauthorization before the old session binding may be changed.
 */
internal class LiveCameraUploadSupervisor(context: Context, private val client: StudioClient, private val uploads: CloudUploadCoordinator) {
    private val app = context.applicationContext
    private val bindings = LiveChunkBindingStore(app)
    private val gate = Mutex()
    private val _status = MutableStateFlow(LiveCameraStatus())
    val status: StateFlow<LiveCameraStatus> = _status
    private var engine: LiveChunkEngine? = null
    private var engineSession: String? = null

    /** Must only be called by a reviewed test activation; no production call site exists. */
    fun start(scope: CoroutineScope, recording: StateFlow<LiveRecorderSnapshot>) = scope.launch(Dispatchers.IO) {
        while (isActive) {
            try { gate.withLock { tick(recording) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { _status.value = LiveCameraStatus(message = "Live-Upload unterbrochen. Das Original bleibt lokal; Wiederaufnahme wird versucht.") }
            delay(15_000)
        }
    }
    private fun activeOriginal(id: String): ActiveOriginal? {
        if (runCatching { UUID.fromString(id).toString() != id }.getOrDefault(true)) return null
        val directory = File(app.filesDir, "camera-recordings")
        val manifest = File(directory, "$id.json")
        if (!manifest.exists() && !File(manifest.path + ".bak").exists()) return null
        val json = AtomicFile(manifest).openRead().bufferedReader(Charsets.UTF_8).use { input ->
            val result = StringBuilder(); val block = CharArray(1024)
            while (true) { val n = input.read(block); if (n < 0) break; check(result.length + n <= 16_384); result.append(block, 0, n) }
            JSONObject(result.toString())
        }
        if (json.getString("recordingId") != id || json.getString("phase") != "recording") return null
        val project = if (json.isNull("projectId")) null else json.getString("projectId")
        if (project != null) check(UUID.fromString(project).toString() == project)
        val origin = RecordingOrigin.read(if (json.has("origin")) json.getString("origin") else null, project)
        if (origin !in setOf(RecordingOrigin.LAN, RecordingOrigin.LAN_UNASSIGNED)) return null
        val media = File(directory, "$id.mp4")
        check(json.getString("file") == media.name && media.isFile && media.canonicalFile.parentFile == directory.canonicalFile && media.canonicalFile.name == media.name)
        return ActiveOriginal(id, project, origin, media)
    }
    private fun normalJournalExists(id: String): Boolean {
        val dir = File(app.filesDir, "camera-upload-journal")
        check(!dir.exists() || dir.isDirectory)
        return dir.listFiles().orEmpty().any { it.name.startsWith("${id}_") && (it.name.endsWith(".json") || it.name.endsWith(".json.bak")) }
    }
    private suspend fun tick(recording: StateFlow<LiveRecorderSnapshot>) {
        val session = client.manualUploadSession()
        val activeId = liveRecorderId(recording.value)
        val pending = bindings.all().filterNot { it.confirmed }
        if (session == null) {
            if (activeId != null || pending.isNotEmpty()) _status.value = LiveCameraStatus(activeId, "Kamera bei Videomaker anmelden; Aufnahme bleibt lokal.")
            return
        }
        val hash = client.manualUploadSessionBinding(session)
        if (activeId != null) {
            val initial = activeOriginal(activeId)
            if (initial != null) {
                val prior = bindings.get(activeId)
                val target = prior?.binding?.projectId ?: initial.project ?: client.ensureLanRecordingProject(activeId, session)
                if (prior != null && prior.binding.sessionHash != hash) {
                    _status.value = LiveCameraStatus(activeId, "Studio-Sitzung geändert. Live-Upload braucht eine erneute Freigabe; Original bleibt lokal.", true); return
                }
                // The whole-file gate closes the race with a recording that finalizes
                // while project creation is in flight. Existing journal means no live path.
                val chosen = uploads.selectLivePath {
                    check(liveRecorderId(recording.value) == activeId)
                    val current = checkNotNull(activeOriginal(activeId)); check(current.project == initial.project && current.origin == initial.origin)
                    check(!normalJournalExists(activeId)) { "Full-file upload already selected" }
                    check(client.manualUploadSessionMatches(session))
                    bindings.bind(LiveChunkBinding(activeId, target, hash))
                } ?: return
                if (!chosen.confirmed && initial.file.length() > 0) {
                    _status.value = LiveCameraStatus(activeId, "Original wird während der Aufnahme übertragen …")
                    engine(session, hash).transfer(chosen.binding, initial.file)
                }
                return
            }
        }
        val saved = savedRecordings(app).associateBy { it.id }
        for (entry in pending) {
            val item = saved[entry.binding.recordingId] ?: continue // incomplete, partial or crashed: never publish
            if (!mayFinalizeLive(entry, item)) continue
            if (entry.binding.sessionHash != hash) { _status.value = LiveCameraStatus(item.id, "Studio-Sitzung geändert. Erneute Freigabe erforderlich; Original bleibt lokal.", true); continue }
            _status.value = LiveCameraStatus(item.id, "Abgeschlossene Aufnahme wird geprüft und übertragen …")
            val original = FinalOriginal(item.bytes, item.sha256)
            val receipt = engine(session, hash).transfer(entry.binding, item.file, original, entry.commitAttempted) { bindings.markCommitAttempted(entry.binding) }
            if (receipt != null) {
                bindings.confirm(entry.binding, original, receipt)
                _status.value = LiveCameraStatus(item.id, "Video in der Cloud bestätigt; Original bleibt lokal.")
            } else _status.value = LiveCameraStatus(item.id, "Serverbeleg noch ungeklärt; Original bleibt lokal.")
            return
        }
    }
    /** Called only after an explicit local camera action, never by automatic retry. */
    suspend fun reauthorize(recordingId: String): Boolean = withContext(Dispatchers.IO) { gate.withLock {
        val entry = bindings.get(recordingId) ?: return@withContext false
        if (entry.confirmed) return@withContext false
        val session = client.manualUploadSession() ?: return@withContext false
        if (!client.projects().any { it.id == entry.binding.projectId } || !client.manualUploadSessionMatches(session)) return@withContext false
        uploads.selectLivePath {
            check(client.manualUploadSessionMatches(session) && !normalJournalExists(recordingId))
            bindings.reauthorize(entry.binding, client.manualUploadSessionBinding(session))
            engine = null; engineSession = null
            true
        } ?: false
    } }
    private fun engine(session: StudioClient.ManualUploadSession, binding: String): LiveChunkEngine {
        if (engine == null || engineSession != binding) {
            engine = LiveChunkEngine(client.experimentalLiveTransport(session)) { client.manualUploadSessionMatches(session) }
            engineSession = binding
        }
        return checkNotNull(engine)
    }
}
