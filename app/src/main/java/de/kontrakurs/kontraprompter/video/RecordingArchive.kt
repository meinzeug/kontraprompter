package de.kontrakurs.kontraprompter.video

import android.content.Context
import android.os.StatFs
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** App-private durable manifest. Failed or interrupted files are never deleted here. */
internal class RecordingArchive private constructor(
    val recordingId: String,
    val projectId: String?,
    val origin: RecordingOrigin,
    val mediaFile: File,
    val manifestFile: File,
    private val createdAtMs: Long,
) {
    companion object {
        const val MIN_FREE_BYTES = 256L * 1024 * 1024
        private const val START_MIN_FREE_BYTES = 384L * 1024 * 1024
        private fun canonicalUuid(value: String): String {
            require(Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$").matches(value)) { "Ungültige Aufnahme- oder Projekt-ID" }
            return UUID.fromString(value).toString()
        }
        fun reserve(context: Context, recordingId: String, projectId: String?, origin: RecordingOrigin = if (projectId == null) RecordingOrigin.MANUAL else RecordingOrigin.WEB): RecordingArchive {
            val id = canonicalUuid(recordingId)
            val project = projectId?.let(::canonicalUuid)
            require(RecordingOrigin.read(origin.wire, project) == origin)
            val dir = File(context.applicationContext.filesDir, "camera-recordings")
            check((dir.exists() || dir.mkdirs()) && dir.isDirectory) { "Aufnahmeverzeichnis nicht verfügbar" }
            check(StatFs(dir.absolutePath).availableBytes >= START_MIN_FREE_BYTES) { "Nicht genügend freier Speicher für eine Aufnahme" }
            val manifest = File(dir, "$id.json")
            val media = File(dir, "$id.mp4")
            check(manifest.createNewFile()) { "Diese Aufnahme-ID existiert bereits" }
            val archive = RecordingArchive(id, project, origin, media, manifest, System.currentTimeMillis())
            try {
                archive.write("preparing")
                check(media.createNewFile()) { "Diese Aufnahmedatei existiert bereits" }
            } catch (e: Exception) {
                runCatching { archive.write("error", "Aufnahme konnte nicht vorbereitet werden") }
                throw e
            }
            return archive
        }
    }

    fun availableBytes(): Long = StatFs(mediaFile.parentFile!!.absolutePath).availableBytes

    @Synchronized fun write(phase: String, error: String? = null, bytes: Long? = null, durationUs: Long? = null, sha256: String? = null, stopReason: String? = null) {
        val data = JSONObject().put("version", 1).put("recordingId", recordingId).put("projectId", projectId).put("origin", origin.wire)
            .put("file", mediaFile.name).put("createdAtMs", createdAtMs).put("updatedAtMs", System.currentTimeMillis()).put("phase", phase)
        if (error != null) data.put("error", error.take(240))
        if (bytes != null) data.put("sizeBytes", bytes)
        if (durationUs != null) data.put("durationUs", durationUs)
        if (sha256 != null) data.put("sha256", sha256)
        if (stopReason != null) data.put("stopReason", stopReason)
        val atomic = AtomicFile(manifestFile)
        val stream = atomic.startWrite()
        try {
            stream.write(data.toString().toByteArray(Charsets.UTF_8))
            stream.fd.sync()
            atomic.finishWrite(stream)
        } catch (error: Exception) {
            atomic.failWrite(stream)
            throw error
        }
    }
}
