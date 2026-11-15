package de.kontrakurs.kontraprompter.cloud

import android.content.Context
import android.system.Os
import android.system.OsConstants
import android.util.AtomicFile
import de.kontrakurs.kontraprompter.video.SavedRecording
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.UUID
import kotlin.coroutines.coroutineContext

/** The unfinished foreground-only workflow is opt-in for isolated tests, never production. */
internal const val MANUAL_UPLOAD_CONTINUATION_ENABLED = false

/** A durable instruction contains no login credential and cannot change its destination on retry. */
internal data class ManualUploadIntent(
    val recordingId: String, val projectId: String, val size: Long, val sha256: String,
    val generation: String, val sessionBinding: String, val state: String,
)

internal enum class ManualResumeDecision { START, PAUSED, COMPLETE, HISTORICAL_CONFIRMATION, BLOCKED, NO_SESSION, SESSION_CHANGED, PROJECT_MISSING, RECORDING_CHANGED, REMOTE_RECORDING }

internal fun finishManualIntent(current: ManualUploadIntent?, generation: String, state: String): ManualUploadIntent? {
    require(state in setOf("paused", "complete", "blocked"))
    return current?.takeIf { it.generation == generation && it.state == "pending" }?.copy(state = state)
}

internal fun manualResumeDecision(intent: ManualUploadIntent, recording: SavedRecording, sessionAvailable: Boolean, sessionUnchanged: Boolean, projectAvailable: Boolean, sameAuthorizedSession: Boolean = true): ManualResumeDecision {
    if (intent.state == "paused") return ManualResumeDecision.PAUSED
    if (intent.state == "complete") return ManualResumeDecision.HISTORICAL_CONFIRMATION
    if (intent.state == "blocked") return ManualResumeDecision.BLOCKED
    if (intent.state != "pending" || intent.recordingId != recording.id || intent.size != recording.bytes || intent.sha256 != recording.sha256 || recording.partial) return ManualResumeDecision.RECORDING_CHANGED
    // Remote-controlled recordings require the independent device READY proof and
    // must remain on the existing remote-camera workflow.
    if (recording.projectId != null) return ManualResumeDecision.REMOTE_RECORDING
    if (!sessionAvailable) return ManualResumeDecision.NO_SESSION
    if (!sessionUnchanged || !sameAuthorizedSession) return ManualResumeDecision.SESSION_CHANGED
    if (!projectAvailable) return ManualResumeDecision.PROJECT_MISSING
    return ManualResumeDecision.START
}

/** Atomic, app-private consent ledger. Files and credentials are never deleted or persisted here. */
internal class ManualUploadIntentStore(context: Context) {
    companion object { private val ioLock = Any() }
    private val app = context.applicationContext
    private fun uuid(value: String): String {
        require(value.matches(Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")))
        return UUID.fromString(value).toString()
    }
    private fun file(recordingId: String): File {
        val directory = File(app.filesDir, "manual-upload-intents")
        check((directory.exists() || directory.mkdirs()) && directory.isDirectory) { "Uploadauftrag kann nicht gespeichert werden." }
        return File(directory, "${uuid(recordingId)}.json")
    }
    fun get(recordingId: String): ManualUploadIntent? = synchronized(ioLock) {
        val f = file(recordingId)
        if (!f.exists() && !File(f.path + ".bak").exists()) return@synchronized null
        val text = AtomicFile(f).openRead().bufferedReader(Charsets.UTF_8).use { input ->
            val result = StringBuilder(); val block = CharArray(2048)
            while (true) { val n = input.read(block); if (n < 0) break; check(result.length + n <= 16_384) { "Uploadauftrag ist beschädigt." }; result.append(block, 0, n) }
            result.toString()
        }
        val data = JSONObject(text)
        check(data.getInt("version") == 1) { "Uploadauftrag ist beschädigt." }
        val entry = ManualUploadIntent(uuid(data.getString("recordingId")), uuid(data.getString("projectId")), data.getLong("sizeBytes"), data.getString("sha256"), uuid(data.getString("generation")), data.getString("sessionBinding"), data.getString("state"))
        check(entry.recordingId == uuid(recordingId) && entry.size in 1..(1024L * 1024 * 1024) && entry.sha256.matches(Regex("^[0-9a-f]{64}$")) && entry.sessionBinding.matches(Regex("^[0-9a-f]{64}$")) && entry.state in setOf("pending", "paused", "complete", "blocked")) { "Uploadauftrag ist beschädigt." }
        entry
    }
    /** Only an explicit user action may create or replace a target. */
    fun start(recording: SavedRecording, projectId: String, sessionBinding: String): ManualUploadIntent = synchronized(ioLock) {
        require(!recording.partial && recording.projectId == null && recording.bytes in 1..(1024L * 1024 * 1024) && recording.sha256.matches(Regex("^[0-9a-f]{64}$"))) { "Diese Aufnahme kann nicht automatisch fortgesetzt werden." }
        require(sessionBinding.matches(Regex("^[0-9a-f]{64}$")))
        val id = uuid(recording.id); val project = uuid(projectId); val previous = get(id)
        check(previous == null || previous.state != "pending" || (previous.projectId == project && previous.size == recording.bytes && previous.sha256 == recording.sha256)) { "Ein anderer Uploadauftrag für diese Aufnahme ist noch aktiv." }
        // Reauthorizing after a new login is an explicit action and creates a new generation.
        val entry = if (previous?.state == "pending" && previous.sessionBinding == sessionBinding) previous else ManualUploadIntent(id, project, recording.bytes, recording.sha256, UUID.randomUUID().toString(), sessionBinding, "pending")
        write(entry); entry
    }
    /** Old callbacks must not overwrite a newer decision or resurrect a pause. */
    fun finish(recordingId: String, generation: String, state: String): Boolean = synchronized(ioLock) {
        require(state in setOf("paused", "complete", "blocked"))
        val next = finishManualIntent(get(recordingId), uuid(generation), state) ?: return@synchronized false
        write(next); true
    }
    private fun write(entry: ManualUploadIntent) {
        val target = file(entry.recordingId); val atomic = AtomicFile(target)
        val payload = JSONObject().put("version", 1).put("recordingId", uuid(entry.recordingId)).put("projectId", uuid(entry.projectId))
            .put("sizeBytes", entry.size).put("sha256", entry.sha256).put("generation", uuid(entry.generation)).put("sessionBinding", entry.sessionBinding).put("state", entry.state)
        val output = atomic.startWrite()
        try { output.write(payload.toString().toByteArray(Charsets.UTF_8)); output.fd.sync(); atomic.finishWrite(output) }
        catch (error: Exception) { atomic.failWrite(output); throw error }
        val directory = target.parentFile!!
        for (path in listOf(directory, directory.parentFile!!)) {
            val fd = Os.open(path.absolutePath, OsConstants.O_RDONLY, 0)
            try { check(OsConstants.S_ISDIR(Os.fstat(fd).st_mode)); Os.fsync(fd) } finally { Os.close(fd) }
        }
    }
}

/** Foreground-only continuation. Callers own its coroutine and cancel on page exit. */
internal class ManualUploadContinuation(context: Context, private val client: StudioClient, private val coordinator: CloudUploadCoordinator) {
    companion object { private val gate = Mutex(); private val race = ManualUploadRaceGuard() }
    private val app = context.applicationContext
    private val store = ManualUploadIntentStore(app)

    suspend fun pending(recordingId: String): ManualUploadIntent? = withContext(Dispatchers.IO) { store.get(recordingId) }

    /** Called only from an explicit choice of destination and an explicit upload action. */
    suspend fun start(recording: SavedRecording, projectId: String): ManualResumeDecision {
        val token = race.explicitStart(recording.id)
        if (!hasExpectedFile(recording)) return ManualResumeDecision.RECORDING_CHANGED
        val session = client.manualUploadSession() ?: return ManualResumeDecision.NO_SESSION
        val projects = client.projects()
        if (!client.manualUploadSessionMatches(session)) return ManualResumeDecision.SESSION_CHANGED
        if (projects.none { it.id == projectId }) return ManualResumeDecision.PROJECT_MISSING
        val persisted = withContext(Dispatchers.IO) {
            race.persistExplicit(token, recording.id) { store.start(recording, projectId, client.manualUploadSessionBinding(session)) }
        }
        if (!persisted) return ManualResumeDecision.PAUSED
        return resume(recording, skipHistoricalConfirmation = false)
    }

    suspend fun pause(recordingId: String): Boolean = withContext(Dispatchers.IO) {
        race.pause(recordingId) {
            val intent = store.get(recordingId)
            intent != null && store.finish(recordingId, intent.generation, "paused")
        }
    }

    suspend fun resume(recording: SavedRecording, skipHistoricalConfirmation: Boolean = true): ManualResumeDecision {
        if (!gate.tryLock()) return ManualResumeDecision.BLOCKED
        val job = coroutineContext[Job] ?: run { gate.unlock(); return ManualResumeDecision.BLOCKED }
        val token = race.snapshot()
        try {
            val intent = withContext(Dispatchers.IO) { store.get(recording.id) } ?: return ManualResumeDecision.BLOCKED
            if (!hasExpectedFile(recording)) return ManualResumeDecision.RECORDING_CHANGED
            val session = client.manualUploadSession()
            val early = manualResumeDecision(intent, recording, session != null, true, true)
            if (early != ManualResumeDecision.START) return early
            val projects = client.projects()
            val decision = manualResumeDecision(intent, recording, true, client.manualUploadSessionMatches(session!!), projects.any { it.id == intent.projectId }, client.manualUploadSessionBinding(session) == intent.sessionBinding)
            if (decision != ManualResumeDecision.START) {
                if (decision == ManualResumeDecision.PROJECT_MISSING) withContext(Dispatchers.IO) { store.finish(recording.id, intent.generation, "blocked") }
                return decision
            }
            val journal = withContext(Dispatchers.IO) { UploadJournal.get(app, recording, intent.projectId) }
            if (journal?.phase == "confirmed" && skipHistoricalConfirmation) {
                val saved = withContext(Dispatchers.IO) { store.finish(recording.id, intent.generation, "complete") }
                return if (saved) ManualResumeDecision.HISTORICAL_CONFIRMATION else ManualResumeDecision.PAUSED
            }
            if (journal?.phase in setOf("project_missing", "conflict")) {
                withContext(Dispatchers.IO) { store.finish(recording.id, intent.generation, "blocked") }
                return ManualResumeDecision.BLOCKED
            }
            val registered = withContext(Dispatchers.IO) {
                race.register(token, recording.id, job) { store.get(recording.id) == intent }
            }
            if (!registered) return ManualResumeDecision.PAUSED
            coroutineContext.ensureActive()
            val result = coordinator.upload(recording, intent.projectId, session)
            return when (val state = result) {
                is CloudUploadState.Confirmed -> {
                    if (!confirmedFor(state, recording.id, intent.projectId)) return ManualResumeDecision.BLOCKED
                    val saved = withContext(Dispatchers.IO) { store.finish(recording.id, intent.generation, "complete") }
                    if (saved) ManualResumeDecision.COMPLETE else ManualResumeDecision.PAUSED
                }
                is CloudUploadState.Failed -> {
                    if (state.recordingId == recording.id && state.projectId == intent.projectId && blocksManualContinuation(state.kind))
                        withContext(Dispatchers.IO) { store.finish(recording.id, intent.generation, "blocked") }
                    ManualResumeDecision.BLOCKED
                }
                else -> ManualResumeDecision.BLOCKED
            }
        } catch (error: CancellationException) { throw error }
        finally { race.release(job); gate.unlock() }
    }

    private fun hasExpectedFile(recording: SavedRecording): Boolean = runCatching {
        val expected = File(File(app.filesDir, "camera-recordings"), "${recording.id}.mp4")
        recording.file.canonicalFile == expected.canonicalFile && recording.file.isFile && recording.file.length() == recording.bytes
    }.getOrDefault(false)
}
