package de.kontrakurs.kontraprompter.video

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Only finalized, locally retained files are offered for export. */
data class SavedRecording(val id: String, val file: File, val bytes: Long, val durationUs: Long, val createdAtMs: Long, val sha256: String, val partial: Boolean, val reason: String, val projectId: String? = null)

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
