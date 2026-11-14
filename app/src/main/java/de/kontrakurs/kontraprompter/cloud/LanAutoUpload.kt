package de.kontrakurs.kontraprompter.cloud

import android.content.Context
import android.system.Os
import android.system.OsConstants
import android.util.AtomicFile
import de.kontrakurs.kontraprompter.video.RecordingOrigin
import de.kontrakurs.kontraprompter.video.SavedRecording
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** A LAN recording's original project and exact bytes are never changed by retry. */
internal data class LanUploadIntent(
    val recordingId: String, val projectId: String, val size: Long, val sha256: String,
    val sessionBinding: String,
)

internal enum class LanUploadDecision { READY, COMPLETE, NEED_LOGIN, SESSION_CHANGED, PROJECT_MISSING, CHANGED, BLOCKED }

/** Pure decision so a missing, changed, or wrong-session file cannot reach the uploader. */
internal fun lanUploadDecision(recording: SavedRecording, intent: LanUploadIntent?, binding: String?, projectAvailable: Boolean, phase: String?, targetProject: String? = recording.projectId ?: intent?.projectId): LanUploadDecision {
    if (recording.origin !in setOf(RecordingOrigin.LAN, RecordingOrigin.LAN_UNASSIGNED) || recording.partial || targetProject == null ||
        (recording.origin == RecordingOrigin.LAN && recording.projectId != targetProject) ||
        (recording.origin == RecordingOrigin.LAN_UNASSIGNED && recording.projectId != null)) return LanUploadDecision.CHANGED
    if (phase == "confirmed") return LanUploadDecision.COMPLETE
    if (phase in setOf("conflict", "project_missing")) return LanUploadDecision.BLOCKED
    if (binding == null) return LanUploadDecision.NEED_LOGIN
    if (intent != null) {
        if (intent.recordingId != recording.id || intent.projectId != targetProject || intent.size != recording.bytes || intent.sha256 != recording.sha256) return LanUploadDecision.CHANGED
        if (intent.sessionBinding != binding) return LanUploadDecision.SESSION_CHANGED
    }
    if (!projectAvailable) return LanUploadDecision.PROJECT_MISSING
    return LanUploadDecision.READY
}

/** Atomic local intent: no password, bearer cookie or media bytes are stored here. */
internal class LanUploadIntentStore(context: Context) {
    companion object { private val lock = Any() }
    private val app = context.applicationContext
    private fun uuid(value: String): String {
        require(value.matches(Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")))
        return UUID.fromString(value).toString()
    }
    private fun file(id: String): File {
        val dir = File(app.filesDir, "lan-upload-intents")
        check((dir.exists() || dir.mkdirs()) && dir.isDirectory) { "Uploadauftrag kann nicht gespeichert werden." }
        return File(dir, "${uuid(id)}.json")
    }
    fun get(id: String): LanUploadIntent? = synchronized(lock) {
        val f = file(id)
        if (!f.exists() && !File(f.path + ".bak").exists()) return@synchronized null
        val text = AtomicFile(f).openRead().bufferedReader(Charsets.UTF_8).use { reader ->
            val out = StringBuilder(); val block = CharArray(1024)
            while (true) { val n = reader.read(block); if (n < 0) break; check(out.length + n <= 16_384); out.append(block, 0, n) }
            out.toString()
        }
        val value = JSONObject(text)
        check(value.getInt("version") == 1)
        val intent = LanUploadIntent(uuid(value.getString("recordingId")), uuid(value.getString("projectId")), value.getLong("sizeBytes"), value.getString("sha256"), value.getString("sessionBinding"))
        check(intent.recordingId == uuid(id) && intent.size in 1..(1024L * 1024 * 1024) && intent.sha256.matches(Regex("^[0-9a-f]{64}$")) && intent.sessionBinding.matches(Regex("^[0-9a-f]{64}$"))) { "Uploadauftrag ist beschädigt." }
        intent
    }
    /** Initial binding is immutable; replacing it is allowed only from a deliberate local action. */
    fun bind(recording: SavedRecording, projectId: String, binding: String, reauthorize: Boolean = false): LanUploadIntent = synchronized(lock) {
        require(!recording.partial && ((recording.origin == RecordingOrigin.LAN && recording.projectId == projectId) ||
            (recording.origin == RecordingOrigin.LAN_UNASSIGNED && recording.projectId == null)))
        require(binding.matches(Regex("^[0-9a-f]{64}$")) && recording.sha256.matches(Regex("^[0-9a-f]{64}$")) && recording.bytes in 1..(1024L * 1024 * 1024))
        val next = LanUploadIntent(uuid(recording.id), uuid(projectId), recording.bytes, recording.sha256, binding)
        val old = get(recording.id)
        if (old != null) {
            check(old.copy(sessionBinding = binding) == next) { "Aufnahme oder Ziel hat sich geändert." }
            check(reauthorize || old.sessionBinding == binding) { "Studio-Sitzung hat sich geändert." }
            if (old == next) return@synchronized old
        }
        val target = file(recording.id); val atomic = AtomicFile(target)
        val payload = JSONObject().put("version", 1).put("recordingId", next.recordingId).put("projectId", next.projectId)
            .put("sizeBytes", next.size).put("sha256", next.sha256).put("sessionBinding", next.sessionBinding)
        val output = atomic.startWrite()
        try { output.write(payload.toString().toByteArray(Charsets.UTF_8)); output.fd.sync(); atomic.finishWrite(output) }
        catch (error: Exception) { atomic.failWrite(output); throw error }
        for (dir in listOf(target.parentFile!!, target.parentFile!!.parentFile!!)) {
            val descriptor = Os.open(dir.absolutePath, OsConstants.O_RDONLY, 0)
            try { check(OsConstants.S_ISDIR(Os.fstat(descriptor).st_mode)); Os.fsync(descriptor) } finally { Os.close(descriptor) }
        }
        next
    }
}

internal data class LanAutoUploadStatus(val recordingId: String? = null, val projectId: String? = null, val message: String = "", val reauthorizeRequired: Boolean = false)

/** Foreground-only queue; after process death a fresh login and explicit reauthorization may be needed. */
internal class LanAutoUploader(context: Context, private val client: StudioClient, private val uploads: CloudUploadCoordinator) {
    companion object { private val gate = Mutex() }
    private val app = context.applicationContext
    private val intents = LanUploadIntentStore(app)
    private val liveBindings = LiveChunkBindingStore(app)
    private val blockedProjectImports = mutableSetOf<String>()
    private val _status = MutableStateFlow(LanAutoUploadStatus())
    val status: StateFlow<LanAutoUploadStatus> = _status

    fun start(scope: CoroutineScope) = scope.launch {
        while (isActive) {
            try { runOnce() } catch (error: CancellationException) { throw error }
            catch (_: Exception) { _status.value = LanAutoUploadStatus(message = "Uploadstatus nicht lesbar. Die Aufnahme bleibt lokal.") }
            delay(15_000)
        }
    }

    internal suspend fun runOnce() = gate.withLock {
        val recordings = withContext(Dispatchers.IO) { savedRecordings(app).filter { it.origin in setOf(RecordingOrigin.LAN, RecordingOrigin.LAN_UNASSIGNED) && !it.partial }.sortedBy { it.createdAtMs } }
        var blocked: LanAutoUploadStatus? = null
        for (recording in recordings) {
            // A crash can leave a live stage behind. Never race it with the legacy
            // whole-file publisher; a deliberate, verified handover is required.
            val live = try { withContext(Dispatchers.IO) { liveBindings.get(recording.id) } }
            catch (_: Exception) { blocked = LanAutoUploadStatus(recording.id, recording.projectId, "Live-Uploadauftrag beschädigt. Das Original bleibt lokal."); continue }
            if (live != null) {
                if (!live.confirmed) blocked = LanAutoUploadStatus(recording.id, live.binding.projectId, "Live-Upload muss fortgesetzt werden. Das Original bleibt lokal.")
                continue
            }
            val intent = try { withContext(Dispatchers.IO) { intents.get(recording.id) } }
            catch (_: Exception) { blocked = LanAutoUploadStatus(recording.id, recording.projectId, "Uploadauftrag beschädigt. Die Aufnahme bleibt lokal."); continue }
            var project = recording.projectId ?: intent?.projectId
            val knownProject = project
            val journal = try { if (knownProject == null) null else withContext(Dispatchers.IO) { UploadJournal.get(app, recording, knownProject) } }
            catch (_: Exception) { blocked = LanAutoUploadStatus(recording.id, project, "Uploadstatus beschädigt. Die Aufnahme bleibt lokal."); continue }
            if (journal?.phase == "confirmed") continue // never resurrect a user-deleted server copy
            if (journal?.phase in setOf("project_missing", "conflict")) { blocked = LanAutoUploadStatus(recording.id, project, "Aufnahme oder Skript muss geprüft werden. Das Original bleibt lokal."); continue }
            val session = client.manualUploadSession()
            if (session == null) { _status.value = LanAutoUploadStatus(recording.id, project, "Für den automatischen Upload Kamera bei Videomaker anmelden."); return@withLock }
            val binding = client.manualUploadSessionBinding(session)
            if (project == null) {
                if (recording.id in blockedProjectImports) { blocked = LanAutoUploadStatus(recording.id, null, "Aufnahmeprojekt muss geprüft werden. Das Original bleibt lokal."); continue }
                _status.value = LanAutoUploadStatus(recording.id, null, "Aufnahmeprojekt wird vorbereitet …")
                project = try { client.ensureLanRecordingProject(recording.id, session) }
                catch (error: CancellationException) { throw error }
                catch (error: UploadException) {
                    if (error.kind == "conflict") blockedProjectImports.add(recording.id)
                    _status.value = LanAutoUploadStatus(recording.id, null, error.message ?: "Aufnahmeprojekt nicht bestätigt. Das Original bleibt lokal."); return@withLock
                }
                catch (_: Exception) { _status.value = LanAutoUploadStatus(recording.id, null, "Aufnahmeprojekt nicht bestätigt. Das Original bleibt lokal."); return@withLock }
            }
            val target = project ?: continue
            // An idempotent project lookup can recover a target after local intent loss.
            // Recheck the durable upload journal before doing anything: a confirmed
            // upload must not be resurrected if the user deleted it on the server.
            val currentJournal = if (knownProject == target) journal else try { withContext(Dispatchers.IO) { UploadJournal.get(app, recording, target) } }
            catch (_: Exception) { blocked = LanAutoUploadStatus(recording.id, target, "Uploadstatus beschädigt. Die Aufnahme bleibt lokal."); continue }
            if (currentJournal?.phase == "confirmed") continue
            if (currentJournal?.phase in setOf("project_missing", "conflict")) { blocked = LanAutoUploadStatus(recording.id, target, "Aufnahme oder Skript muss geprüft werden. Das Original bleibt lokal."); continue }
            val initial = lanUploadDecision(recording, intent, binding, true, currentJournal?.phase, target)
            if (initial != LanUploadDecision.READY) {
                blocked = LanAutoUploadStatus(recording.id, target, if (initial == LanUploadDecision.SESSION_CHANGED) "Studio-Sitzung geändert. Upload für diese Aufnahme erneut freigeben." else "Aufnahme oder Uploadauftrag hat sich geändert.", reauthorizeRequired = initial == LanUploadDecision.SESSION_CHANGED)
                continue
            }
            _status.value = LanAutoUploadStatus(recording.id, target, if (recording.origin == RecordingOrigin.LAN_UNASSIGNED) "Aufnahmeprojekt wird geprüft …" else "Skriptzuordnung wird geprüft …")
            val available = try { client.projects().any { it.id == target } } catch (error: CancellationException) { throw error }
            catch (_: Exception) { _status.value = LanAutoUploadStatus(recording.id, target, "Videomaker nicht erreichbar oder Anmeldung abgelaufen. Das Original bleibt lokal."); return@withLock }
            if (!client.manualUploadSessionMatches(session)) { _status.value = LanAutoUploadStatus(recording.id, target, "Studio-Sitzung hat sich geändert. Das Original bleibt lokal."); return@withLock }
            if (!available) { blocked = LanAutoUploadStatus(recording.id, target, "Zugehöriges Skript ist nicht verfügbar. Das Original bleibt lokal."); continue }
            if (intent == null) withContext(Dispatchers.IO) { intents.bind(recording, target, binding) }
            _status.value = LanAutoUploadStatus(recording.id, target, "Aufnahme wird zu Videomaker übertragen …")
            val result = uploads.upload(recording, target, session)
            _status.value = when (result) {
                is CloudUploadState.Confirmed -> if (confirmedFor(result, recording.id, target)) LanAutoUploadStatus(recording.id, target, if (recording.origin == RecordingOrigin.LAN_UNASSIGNED) "Video im Aufnahmeprojekt ohne Skript bestätigt · Original bleibt lokal" else "Video für das Skript bestätigt · Original bleibt lokal") else LanAutoUploadStatus(recording.id, target, "Serverbeleg passt nicht; Original bleibt lokal.")
                is CloudUploadState.Failed -> LanAutoUploadStatus(recording.id, target, result.message)
                else -> LanAutoUploadStatus(recording.id, target, "Upload nicht bestätigt · Original bleibt lokal")
            }
            return@withLock // one upload at a time; network failures retry on the next tick
        }
        _status.value = blocked ?: LanAutoUploadStatus()
    }

    /** A local explicit action can rebind only the same unchanged recording/project. */
    suspend fun reauthorize(recordingId: String): Boolean = gate.withLock {
        val recording = withContext(Dispatchers.IO) { savedRecordings(app).firstOrNull { it.id == recordingId && it.origin in setOf(RecordingOrigin.LAN, RecordingOrigin.LAN_UNASSIGNED) && !it.partial } } ?: return@withLock false
        val project = recording.projectId ?: withContext(Dispatchers.IO) { intents.get(recording.id)?.projectId } ?: return@withLock false
        val session = client.manualUploadSession() ?: return@withLock false
        val available = try { client.projects().any { it.id == project } } catch (error: CancellationException) { throw error } catch (_: Exception) { false }
        if (!available || !client.manualUploadSessionMatches(session)) return@withLock false
        withContext(Dispatchers.IO) { intents.bind(recording, project, client.manualUploadSessionBinding(session), reauthorize = true) }
        true
    }
}
