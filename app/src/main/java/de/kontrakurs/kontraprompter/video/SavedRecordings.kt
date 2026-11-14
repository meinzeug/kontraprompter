package de.kontrakurs.kontraprompter.video

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Only finalized, locally retained files are offered for export. */
data class SavedRecording(val id: String, val file: File, val bytes: Long, val durationUs: Long, val createdAtMs: Long, val sha256: String, val partial: Boolean, val reason: String, val projectId: String? = null)

/** A leftover is exportable as raw data only, never as a validated recording or cloud upload. */
data class UnverifiedRecording(val id: String, val file: File, val bytes: Long)

fun unverifiedRecordings(context: Context): List<UnverifiedRecording> {
    val dir = File(context.applicationContext.filesDir, "camera-recordings")
    val validated = savedRecordings(context).map { it.id }.toSet()
    return unverifiedRecordingsInDirectory(dir, validated)
}

internal fun unverifiedRecordingsInDirectory(dir: File, validated: Set<String>): List<UnverifiedRecording> {
    return dir.listFiles().orEmpty().mapNotNull { file ->
        runCatching {
            val id = file.name.removeSuffix(".mp4")
            require(file.name == "$id.mp4" && UUID.fromString(id).toString() == id && id !in validated)
            require(file.canonicalFile.parentFile == dir.canonicalFile && file.canonicalFile.name == file.name && file.isFile && file.length() > 0)
            UnverifiedRecording(id, file, file.length())
        }.getOrNull()
    }.sortedBy { it.id }
}

fun recordingStopDescription(reason: String): String? = when (reason.lowercase()) {
    "storage" -> "Automatisch beendet: Gerätespeicher fast voll."
    "size_limit" -> "Automatisch beendet: maximale Dateigröße erreicht."
    "duration_limit" -> "Automatisch beendet: maximale Aufnahmedauer erreicht."
    "error" -> "Unvollständige Aufnahme nach einem Fehler."
    "user" -> null
    else -> "Die Aufnahme wurde automatisch beendet."
}

fun savedRecordings(context: Context): List<SavedRecording> {
    val dir = File(context.applicationContext.filesDir, "camera-recordings")
    return dir.listFiles { file -> file.isFile && file.name.endsWith(".json") }.orEmpty().mapNotNull { manifest ->
        runCatching {
            require(manifest.length() in 1..16_384)
            val data = JSONObject(manifest.readText())
            val phase = data.getString("phase")
            require(phase == "ready" || phase == "partial")
            val id = UUID.fromString(data.getString("recordingId")).toString()
            require(manifest.name == "$id.json")
            val file = File(dir, "$id.mp4")
            val bytes = data.getLong("sizeBytes")
            val duration = data.getLong("durationUs")
            val sha = data.getString("sha256")
            require(file.isFile && bytes > 0 && file.length() == bytes && duration > 0 && sha.matches(Regex("^[0-9a-f]{64}$")))
            val projectId = if (data.isNull("projectId")) null else UUID.fromString(data.getString("projectId")).toString()
            SavedRecording(id, file, bytes, duration, data.getLong("createdAtMs"), sha, phase == "partial", data.optString("stopReason", "user"), projectId)
        }.getOrNull()
    }.sortedByDescending { it.createdAtMs }
}
