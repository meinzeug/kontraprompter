package de.kontrakurs.kontraprompter.camera

import android.content.Context
import android.system.Os
import android.system.OsConstants
import android.util.AtomicFile
import de.kontrakurs.kontraprompter.video.RecordingVerifier
import de.kontrakurs.kontraprompter.video.savedRecordings
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.UUID

/** All decisions are synchronously durable before they can be acknowledged or executed. */
internal class RemoteCameraJournal private constructor(context: Context, private val deviceId: String) {
    private val app = context.applicationContext
    private val dir = File(app.filesDir, "remote-camera")
    private val target = File(dir, cameraJournalFileName(deviceId))
    @Volatile private var broken = false
    private var current: CameraDecisionState

    init {
        check((dir.isDirectory || dir.mkdirs()) && dir.isDirectory) { "Camera journal directory is unavailable" }
        syncDirectory(dir.parentFile!!)
        current = try { load() }
        catch (error: org.json.JSONException) { throw CameraIdentityRecoveryRequiredException() }
        catch (error: IllegalStateException) { throw CameraIdentityRecoveryRequiredException() }
        catch (error: IllegalArgumentException) { throw CameraIdentityRecoveryRequiredException() }
        val recovered = reconcile(recoverCameraCaptures(current), app)
        if (recovered != current) commit(recovered)
    }

    @Synchronized fun snapshot(): CameraDecisionState { check(!broken); return current }

    @Synchronized fun reconcilePending(): Boolean {
        check(!broken)
        val next = reconcile(current, app)
        if (next != current) commit(next)
        return next.captures.none { it.phase == "recovering" }
    }

    @Synchronized fun reserveSequence(): Long {
        check(!broken)
        val next = reserveCameraSequence(current)
        commit(next)
        return next.sequence
    }

    @Synchronized fun decide(command: CameraCommand, expired: Boolean, canStart: Boolean): CameraDecisionOutcome {
        check(!broken)
        val outcome = decideCameraCommand(current, command, expired, canStart)
        commit(outcome.state)
        return outcome
    }

    @Synchronized fun capture(recordingId: String, phase: String) {
        check(!broken)
        commit(updateCameraCapture(current, recordingId, phase))
    }

    @Synchronized fun captureVerified(recordingId: String, phase: String) {
        check(!broken)
        val capture = current.captures.firstOrNull { it.recordingId == recordingId } ?: error("Unknown recording")
        if (capture.phase == phase && capture.sizeBytes != null && capture.sha256 != null) return
        // Preserve the terminal local phase before an expensive, retryable media verification.
        if (capture.phase != phase) commit(updateCameraCapture(current, recordingId, phase))
        val proof = try { verifySaved(app, capture, phase) } catch (_: Exception) { throw CameraReconciliationPendingException() }
        commit(updateCameraCaptureProof(current, recordingId, phase, proof.first, proof.second))
    }

    @Synchronized fun adoptLocal(recordingId: String, phase: String) {
        check(!broken && uuid(recordingId) == recordingId && phase in allowedCapturePhases)
        if (current.captures.none { it.recordingId == recordingId }) {
            check(current.captures.size < MAX_CAMERA_DECISIONS)
            commit(current.copy(captures = current.captures + CameraCapture(recordingId, null, phase)))
        }
    }

    @Synchronized fun confirmAcks(revisions: Map<String, Long>) {
        check(!broken)
        commit(confirmCameraAcks(current, revisions))
    }

    @Synchronized fun confirmReport(recordingId: String, phase: String) {
        check(!broken)
        commit(confirmCameraCaptureReport(current, recordingId, phase))
    }

    @Synchronized fun retryReport(recordingId: String, phase: String) {
        check(!broken)
        commit(invalidateCameraCaptureReport(current, recordingId, phase))
    }

    private fun commit(next: CameraDecisionState) {
        try {
            val payload = JSONObject().put("version", 1).put("sequence", next.sequence)
                .put("decisions", JSONArray().apply { next.decisions.forEach { d -> put(JSONObject()
                    .put("id", d.command.id).put("type", d.command.type).put("recordingId", d.command.recordingId)
                    .put("projectId", d.command.projectId ?: JSONObject.NULL).put("result", d.result).put("pendingAck", d.pendingAck).put("revision", d.revision)) } })
                .put("captures", JSONArray().apply { next.captures.forEach { c -> put(JSONObject()
                    .put("recordingId", c.recordingId).put("projectId", c.projectId ?: JSONObject.NULL).put("phase", c.phase).put("reportedPhase", c.reportedPhase ?: JSONObject.NULL)
                    .put("sizeBytes", c.sizeBytes ?: JSONObject.NULL).put("sha256", c.sha256 ?: JSONObject.NULL)) } })
                .toString().toByteArray(Charsets.UTF_8)
            check(payload.size <= MAX_BYTES) { "Camera journal is full" }
            val atomic = AtomicFile(target)
            val output = atomic.startWrite()
            try { output.write(payload); output.fd.sync(); atomic.finishWrite(output) }
            catch (error: Exception) { atomic.failWrite(output); throw error }
            syncDirectory(dir)
            current = next
        } catch (error: Exception) { broken = true; throw CameraJournalFailureException(error) }
    }

    private fun load(): CameraDecisionState {
        if (!target.exists() && !File(target.path + ".bak").exists()) throw CameraIdentityRecoveryRequiredException()
        val text = AtomicFile(target).openRead().bufferedReader(Charsets.UTF_8).use { input ->
            val result = StringBuilder(); val buffer = CharArray(2048)
            while (true) { val count = input.read(buffer); if (count < 0) break; check(result.length + count <= MAX_BYTES); result.append(buffer, 0, count) }
            result.toString()
        }
        val root = JSONObject(text)
        check(root.getInt("version") == 1)
        val rawSequence = if (root.has("sequence")) root.get("sequence") else 0
        check(rawSequence is Int || rawSequence is Long)
        val sequence = (rawSequence as Number).toLong(); check(sequence in 0..MAX_CAMERA_SEQUENCE)
        val rawDecisions = root.getJSONArray("decisions"); val rawCaptures = root.getJSONArray("captures")
        check(rawDecisions.length() <= MAX_CAMERA_DECISIONS && rawCaptures.length() <= MAX_CAMERA_DECISIONS)
        val decisions = (0 until rawDecisions.length()).map { index -> rawDecisions.getJSONObject(index).let { d ->
            val project = if (d.isNull("projectId")) null else d.getString("projectId")
            val command = CameraCommand(uuid(d.getString("id")), d.getString("type"), uuid(d.getString("recordingId")), project?.let(::uuid))
            check(command.type == "record_start" && project != null || command.type == "record_stop" && project == null)
            val result = d.getString("result"); check(result in setOf("accepted", "rejected", "expired"))
            CameraDecision(command, result, d.getBoolean("pendingAck"), d.getLong("revision").also { check(it > 0) })
        } }
        val captures = (0 until rawCaptures.length()).map { index -> rawCaptures.getJSONObject(index).let { c ->
            val phase = c.getString("phase").also { check(it in allowedCapturePhases) }
            val reported = if (c.isNull("reportedPhase")) null else c.getString("reportedPhase").also { check(it in allowedCapturePhases) }
            val size = if (c.isNull("sizeBytes")) null else c.get("sizeBytes").let { check(it is Int || it is Long); (it as Number).toLong() }
            val sha = if (c.isNull("sha256")) null else c.getString("sha256")
            check((size == null && sha == null) || (phase in setOf("ready", "partial") && size != null && size in 1..1_073_741_824L && sha != null && sha.matches(Regex("^[0-9a-f]{64}$"))))
            CameraCapture(uuid(c.getString("recordingId")), if (c.isNull("projectId")) null else uuid(c.getString("projectId")), phase, reported, size, sha)
        } }
        check(decisions.map { it.command.id }.toSet().size == decisions.size)
        check(captures.map { it.recordingId }.toSet().size == captures.size)
        return CameraDecisionState(decisions, captures, sequence)
    }

    /** Reconcile a finalized local file before declaring an interrupted capture failed. */
    private fun reconcile(state: CameraDecisionState, context: Context): CameraDecisionState {
        return state.copy(captures = state.captures.map { capture ->
            if (capture.phase != "recovering" && !(capture.phase in setOf("ready", "partial") && capture.sizeBytes == null)) return@map capture
            val pending = capture.copy(phase = "recovering", reportedPhase = null, sizeBytes = null, sha256 = null)
            val archiveDir = File(context.filesDir, "camera-recordings")
            val manifest = File(archiveDir, "${capture.recordingId}.json")
            val backup = File(manifest.path + ".bak"); val newFile = File(manifest.path + ".new")
            if (Files.notExists(archiveDir.toPath()) || (Files.notExists(manifest.toPath()) && Files.notExists(backup.toPath()) && Files.notExists(newFile.toPath()))) {
                return@map pending.copy(phase = "error")
            }
            val phase = runCatching {
                val data = AtomicFile(manifest).openRead().bufferedReader(Charsets.UTF_8).use { input ->
                    val value = StringBuilder(); val block = CharArray(1024)
                    while (true) { val count = input.read(block); if (count < 0) break; check(value.length + count <= 16_384); value.append(block, 0, count) }
                    JSONObject(value.toString())
                }
                check(data.getString("recordingId") == capture.recordingId && (if (data.isNull("projectId")) null else data.getString("projectId")) == capture.projectId)
                data.getString("phase")
            }.getOrNull() ?: return@map pending
            if (phase in setOf("preparing", "recording", "finalizing", "error")) return@map pending.copy(phase = "error")
            if (phase != "ready" && phase != "partial") return@map pending
            val proof = runCatching { verifySaved(context, capture, phase) }.getOrNull() ?: return@map pending
            pending.copy(phase = phase, sizeBytes = proof.first, sha256 = proof.second)
        })
    }

    private fun verifySaved(context: Context, capture: CameraCapture, phase: String): Pair<Long, String> {
        require(phase == "ready" || phase == "partial")
        val item = savedRecordings(context).firstOrNull { it.id == capture.recordingId } ?: error("Lokale Aufnahme nicht verfügbar")
        check(item.projectId == capture.projectId && item.partial == (phase == "partial")) { "Aufnahmezuordnung stimmt nicht" }
        val verified = RecordingVerifier.verify(item.file, item.partial)
        check(verified.bytes == item.bytes && verified.sha256 == item.sha256) { "Aufnahmedatei stimmt nicht mit dem Manifest überein" }
        return verified.bytes to verified.sha256
    }

    private fun uuid(value: String): String = UUID.fromString(value).toString().also { check(it == value) }
    private fun syncDirectory(path: File) {
        val descriptor = Os.open(path.absolutePath, OsConstants.O_RDONLY, 0)
        try { check(OsConstants.S_ISDIR(Os.fstat(descriptor).st_mode)); Os.fsync(descriptor) } finally { Os.close(descriptor) }
    }

    companion object {
        private const val MAX_BYTES = 8 * 1024 * 1024
        private val instances = mutableMapOf<String, RemoteCameraJournal>()
        /** Only the identity initialization transaction may create an empty journal. */
        @Synchronized fun ensureInitialized(dir: File, deviceId: String, mayCreate: Boolean, mayMigrate: Boolean) {
            val target = File(dir, cameraJournalFileName(deviceId))
            fun present() = target.exists() || File(target.path + ".bak").exists()
            if (!present() && mayMigrate) migrateLegacy(dir, deviceId)
            if (present()) return
            if (!mayCreate) throw CameraIdentityRecoveryRequiredException()
            val data = JSONObject().put("version", 1).put("sequence", 0)
                .put("decisions", JSONArray()).put("captures", JSONArray()).toString().toByteArray(Charsets.UTF_8)
            val atomic = AtomicFile(target); val out = atomic.startWrite()
            try { out.write(data); out.fd.sync(); atomic.finishWrite(out) }
            catch (error: Exception) { atomic.failWrite(out); throw error }
            syncDir(dir)
        }
        @Synchronized fun open(context: Context, deviceId: String): RemoteCameraJournal {
            require(UUID.fromString(deviceId).toString() == deviceId)
            val application = context.applicationContext
            val existing = instances[deviceId]
            if (existing != null) {
                check(existing.dir.parentFile?.canonicalFile == application.filesDir.canonicalFile)
                // A failed write left the in-memory owner unusable. Never silently reuse it
                // or start executing commands with uncertain durable history.
                if (existing.broken) throw CameraIdentityRecoveryRequiredException()
                return existing
            }
            return RemoteCameraJournal(application, deviceId).also { instances[deviceId] = it }
        }

        /** Bind an older unscoped journal before changing identity; never delete the old file. */
        @Synchronized fun bindLegacyOwner(context: Context, oldDeviceId: String?) {
            val dir = File(context.applicationContext.filesDir, "remote-camera")
            check((dir.isDirectory || dir.mkdirs()) && dir.isDirectory)
            val legacy = File(dir, "decisions.json")
            if (!legacy.exists() && !File(legacy.path + ".bak").exists()) return
            val owner = File(dir, "legacy-owner.json")
            if (owner.exists() || File(owner.path + ".bak").exists()) return
            oldDeviceId?.let { require(UUID.fromString(it).toString() == it) }
            val data = JSONObject().put("deviceId", oldDeviceId ?: JSONObject.NULL).toString().toByteArray(Charsets.UTF_8)
            val atomic = AtomicFile(owner); val out = atomic.startWrite()
            try { out.write(data); out.fd.sync(); atomic.finishWrite(out) }
            catch (error: Exception) { atomic.failWrite(out); throw error }
            syncDir(dir)
        }

        private fun migrateLegacy(dir: File, deviceId: String) {
            val legacy = File(dir, "decisions.json")
            if (!legacy.exists() && !File(legacy.path + ".bak").exists()) return
            val ownerFile = File(dir, "legacy-owner.json")
            if (!ownerFile.exists() && !File(ownerFile.path + ".bak").exists()) {
                val atomic = AtomicFile(ownerFile); val out = atomic.startWrite()
                try { out.write(JSONObject().put("deviceId", deviceId).toString().toByteArray(Charsets.UTF_8)); out.fd.sync(); atomic.finishWrite(out) }
                catch (error: Exception) { atomic.failWrite(out); throw error }
                syncDir(dir)
            }
            val owner = try { AtomicFile(ownerFile).openRead().bufferedReader(Charsets.UTF_8).use { input ->
                val text = StringBuilder(); val block = CharArray(1024)
                while (true) { val count = input.read(block); if (count < 0) break; check(text.length + count <= 4096); text.append(block, 0, count) }
                JSONObject(text.toString())
            } } catch (_: org.json.JSONException) { throw CameraIdentityRecoveryRequiredException() }
              catch (_: IllegalStateException) { throw CameraIdentityRecoveryRequiredException() }
            val ownerId = try { if (owner.isNull("deviceId")) null else owner.getString("deviceId") }
                catch (_: org.json.JSONException) { throw CameraIdentityRecoveryRequiredException() }
            if (!legacyCameraJournalBelongsTo(ownerId, deviceId)) return
            val destination = File(dir, cameraJournalFileName(deviceId))
            if (destination.exists() || File(destination.path + ".bak").exists()) return
            val bytes = AtomicFile(legacy).openRead().use { input ->
                val output = java.io.ByteArrayOutputStream(); val block = ByteArray(8192)
                while (true) { val count = input.read(block); if (count < 0) break; check(output.size() + count <= MAX_BYTES); output.write(block, 0, count) }
                output.toByteArray()
            }
            val atomic = AtomicFile(destination); val out = atomic.startWrite()
            try { out.write(bytes); out.fd.sync(); atomic.finishWrite(out) }
            catch (error: Exception) { atomic.failWrite(out); throw error }
            syncDir(dir)
        }

        private fun syncDir(path: File) {
            val fd = Os.open(path.absolutePath, OsConstants.O_RDONLY, 0)
            try { check(OsConstants.S_ISDIR(Os.fstat(fd).st_mode)); Os.fsync(fd) } finally { Os.close(fd) }
        }
    }
}

internal class CameraReconciliationPendingException : IOException("Lokale Aufnahme wird erneut geprüft; das Original bleibt erhalten.")
internal class CameraJournalFailureException(cause: Throwable) : IllegalStateException("Kamerajournal kann nicht sicher gespeichert werden.", cause)
