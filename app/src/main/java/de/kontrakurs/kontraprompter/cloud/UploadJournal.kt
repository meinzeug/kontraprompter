package de.kontrakurs.kontraprompter.cloud

import android.content.Context
import android.util.AtomicFile
import android.system.Os
import android.system.OsConstants
import de.kontrakurs.kontraprompter.video.SavedRecording
import de.kontrakurs.kontraprompter.video.cameraClaimRecordingId
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Upload decisions are persisted separately from the immutable local recording. */
internal data class UploadEntry(val recordingId: String, val projectId: String, val uploadId: String, val size: Long, val sha256: String, val phase: String, val cameraRecordingId: String? = null)

internal object UploadJournal {
    private fun uuid(value: String): String {
        require(value.matches(Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")))
        return UUID.fromString(value).toString()
    }
    private fun file(context: Context, recording: String, project: String): File {
        val dir = File(context.applicationContext.filesDir, "camera-upload-journal")
        check((dir.exists() || dir.mkdirs()) && dir.isDirectory) { "Uploadstatus kann nicht gespeichert werden." }
        return File(dir, "${uuid(recording)}_${uuid(project)}.json")
    }
    @Synchronized fun get(context: Context, recording: SavedRecording, projectId: String): UploadEntry? {
        val project = uuid(projectId); val f = file(context, recording.id, project)
        if (!f.exists() && !File(f.path + ".bak").exists()) return null
        val text = AtomicFile(f).openRead().bufferedReader(Charsets.UTF_8).use { input ->
            val result = StringBuilder(); val chars = CharArray(2048)
            while (true) { val count = input.read(chars); if (count < 0) break; check(result.length + count <= 16_384) { "Uploadstatus ist beschädigt." }; result.append(chars, 0, count) }
            result.toString()
        }
        check(text.isNotEmpty()) { "Uploadstatus ist beschädigt." }
        val data = JSONObject(text)
        check(data.getInt("version") == 1) { "Uploadstatus ist beschädigt." }
        val cameraId = if (data.isNull("cameraRecordingId")) null else uuid(data.getString("cameraRecordingId"))
        val entry = UploadEntry(uuid(data.getString("recordingId")), uuid(data.getString("projectId")), uuid(data.getString("uploadId")), data.getLong("sizeBytes"), data.getString("sha256"), data.getString("phase"), cameraId)
        val expectedCameraId = cameraClaimRecordingId(recording.origin, uuid(recording.id), recording.projectId, project)
        check(entry.recordingId == uuid(recording.id) && entry.projectId == project && entry.size == recording.bytes && entry.sha256 == recording.sha256 && entry.cameraRecordingId == expectedCameraId && entry.phase in setOf("uploading", "confirmed", "uncertain", "project_missing", "conflict")) { "Uploadstatus passt nicht zur lokalen Aufnahme." }
        return entry
    }
    @Synchronized fun begin(context: Context, recording: SavedRecording, projectId: String): UploadEntry {
        val project = uuid(projectId)
        val old = get(context, recording, project)
        val cameraId = cameraClaimRecordingId(recording.origin, uuid(recording.id), recording.projectId, project)
        val entry = (old ?: UploadEntry(uuid(recording.id), project, uploadIdFor(recording.id, project), recording.bytes, recording.sha256, "uncertain", cameraId)).copy(phase = "uploading")
        write(context, entry)
        return entry
    }
    @Synchronized fun write(context: Context, entry: UploadEntry) {
        val atomic = AtomicFile(file(context, entry.recordingId, entry.projectId))
        val payload = JSONObject().put("version", 1).put("recordingId", uuid(entry.recordingId)).put("projectId", uuid(entry.projectId))
            .put("uploadId", uuid(entry.uploadId)).put("sizeBytes", entry.size).put("sha256", entry.sha256).put("phase", entry.phase).put("cameraRecordingId", entry.cameraRecordingId ?: JSONObject.NULL)
        val output = atomic.startWrite()
        try { output.write(payload.toString().toByteArray(Charsets.UTF_8)); output.fd.sync(); atomic.finishWrite(output) }
        catch (error: Exception) { atomic.failWrite(output); throw error }
        val parent = file(context, entry.recordingId, entry.projectId).parentFile!!
        for (path in listOf(parent, parent.parentFile!!)) {
            val directory = Os.open(path.absolutePath, OsConstants.O_RDONLY, 0)
            try { check(OsConstants.S_ISDIR(Os.fstat(directory).st_mode)); Os.fsync(directory) } finally { Os.close(directory) }
        }
    }
}
