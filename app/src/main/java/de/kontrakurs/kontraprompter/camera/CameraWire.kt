package de.kontrakurs.kontraprompter.camera

import org.json.JSONObject
import java.util.UUID
import java.io.IOException

internal class CameraProtocolException(message: String, cause: Throwable? = null) : IOException(message, cause)

internal data class CameraPollResponse(val command: CameraCommand?, val remainingMs: Long)

/** Translate malformed or legacy-v2 responses into the non-destructive retry path. */
internal fun decodeCameraPollResponse(text: String): CameraPollResponse = try { parseCameraPollResponse(text) }
    catch (error: Exception) { throw CameraProtocolException("Ungültige Antwort der Kamerafernsteuerung. Bitte Versionen prüfen.", error) }

/** Strictly validates the actual poll response before it reaches the decision journal. */
internal fun parseCameraPollResponse(text: String): CameraPollResponse {
    require(text.length <= 16_384)
    val root = JSONObject(text)
    val items = root.getJSONArray("commands")
    require(items.length() <= 1)
    if (items.length() == 0) return CameraPollResponse(null, 0)
    val item = items.getJSONObject(0)
    fun requiredString(name: String): String = (item.opt(name) as? String) ?: error("Ungültiger Kamerabefehl")
    fun nullableString(name: String): String? {
        require(item.has(name))
        val value = item.opt(name)
        return if (value == null || value === JSONObject.NULL) null else value as? String ?: error("Ungültiger Kamerabefehl")
    }
    fun canonical(value: String): Boolean = runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)
    val id = requiredString("id")
    val type = requiredString("type")
    val recording = requiredString("recordingId")
    val project = nullableString("projectId")
    val rawFrom = item.opt("cameraFrom")
    val from = if (rawFrom == null || rawFrom === JSONObject.NULL) null else rawFrom as? Boolean ?: error("Ungültiger Kamerabefehl")
    val rawRemaining = item.opt("remainingMs")
    val remaining = when (rawRemaining) { is Int -> rawRemaining.toLong(); is Long -> rawRemaining; else -> error("Ungültige Befehlsfrist") }
    require(canonical(id) && canonical(recording) && remaining in 0..10_000)
    require(type in setOf("record_start", "record_stop", "record_pause", "record_resume", "camera_switch"))
    require(if (type in setOf("record_start", "record_pause", "record_resume")) project != null && canonical(project) else project == null)
    require(if (type == "camera_switch") id == recording && from != null else from == null)
    return CameraPollResponse(CameraCommand(id, type, recording, project, from), remaining)
}
