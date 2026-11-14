package de.kontrakurs.kontraprompter.cloud

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.util.UUID

/** Actual phone heartbeat/command wire format, kept independent for device-level testing. */
data class StudioCommand(val id: String, val type: String, val projectId: String, val remainingMs: Long, val value: Double? = null)
internal fun pollPrompterDevice(
    open: (String, String) -> HttpURLConnection,
    origin: String,
    session: String,
    sessionMatches: () -> Boolean,
    invalidateSession: () -> Unit,
    id: String, name: String, status: String, acknowledged: List<String>, speed: Float, fontSize: Float,
): List<StudioCommand> {
    require(speed.isFinite() && speed in 5f..160f && fontSize.isFinite() && fontSize in 20f..100f)
    check(sessionMatches()) { "Studio-Sitzung hat sich geändert." }
    val connection = open("/api/devices/poll", "POST")
    try {
        connection.setRequestProperty("Cookie", session)
        connection.setRequestProperty("Origin", origin)
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        connection.doOutput = true
        val payload = JSONObject().put("id", id).put("name", name.take(80)).put("role", "prompter").put("status", status.take(120)).put("ack", JSONArray(acknowledged))
            .put("controls", JSONObject().put("speed", speed.toDouble()).put("fontSize", fontSize.toDouble()))
        connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
        if (connection.responseCode == 401 || connection.responseCode == 403) { invalidateSession(); throw IllegalStateException("Studio-Sitzung abgelaufen.") }
        if (connection.responseCode != 200) throw IllegalStateException("Fernsteuerung nicht erreichbar.")
        val text = connection.inputStream.bufferedReader(Charsets.UTF_8).use { input ->
            val result = StringBuilder(); val block = CharArray(1024)
            while (true) { val count = input.read(block); if (count < 0) break; check(result.length + count <= 16_384); result.append(block, 0, count) }
            result.toString()
        }
        check(sessionMatches()) { "Studio-Sitzung hat sich geändert." }
        val array = JSONObject(text).getJSONArray("commands"); check(array.length() <= 1)
        return (0 until array.length()).map { i -> array.getJSONObject(i).let { item ->
            val raw = if (item.isNull("value")) null else item.get("value")
            check(raw == null || raw is Number) { "Ungültiger Fernsteuerungswert." }
            val commandId = item.getString("id"); val type = item.getString("type"); val remaining = item.getLong("remainingMs")
            check(runCatching { UUID.fromString(commandId).toString() == commandId }.getOrDefault(false) && remaining in 0..10_000)
            check(type in setOf("load", "play", "pause", "reset", "set_speed", "set_font_size"))
            StudioCommand(commandId, type, item.optString("projectId", ""), remaining, (raw as? Number)?.toDouble())
        } }
    } finally { connection.disconnect() }
}
