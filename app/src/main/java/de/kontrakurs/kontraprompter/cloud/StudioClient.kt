package de.kontrakurs.kontraprompter.cloud

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** HTTPS-only, in-memory session. No password or session cookie is persisted. */
class StudioClient {
    companion object { private const val BASE = "https://videomaker.dorfspy.de" }
    @Volatile private var cookie: String? = null

    suspend fun login(password: String): Unit = withContext(Dispatchers.IO) {
        cookie = null
        val connection = open("/api/login", "POST")
        try {
            connection.doOutput = true
            connection.setRequestProperty("Origin", BASE)
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.outputStream.use { it.write(JSONObject().put("password", password).toString().toByteArray(Charsets.UTF_8)) }
            if (connection.responseCode != 200) throw IllegalStateException(if (connection.responseCode == 429) "Zu viele Anmeldeversuche. Bitte später erneut versuchen." else "Anmeldung fehlgeschlagen. Passwort und Verbindung prüfen.")
            val session = connection.headerFields.entries.filter { it.key.equals("Set-Cookie", true) }
                .flatMap { it.value }.map { it.substringBefore(';') }.firstOrNull { it.startsWith("kontrakurs_session=") }
                ?: throw IllegalStateException("Der Server hat keine Sitzung eingerichtet.")
            cookie = session
        } finally { connection.disconnect() }
    }

    suspend fun projects(): List<StudioProject> = withContext(Dispatchers.IO) {
        val connection = open("/api/projects", "GET")
        try {
            connection.setRequestProperty("Cookie", cookie ?: throw IllegalStateException("Bitte erneut anmelden."))
            if (connection.responseCode == 401) { cookie = null; throw IllegalStateException("Sitzung abgelaufen. Bitte erneut anmelden.") }
            if (connection.responseCode != 200) throw IllegalStateException("Skripte konnten nicht geladen werden.")
            val array = JSONArray(connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
            (0 until array.length()).map { i -> array.getJSONObject(i).let { p -> StudioProject(p.getString("id"), p.getString("title"), p.getJSONObject("content").getString("script")) } }
        } finally { connection.disconnect() }
    }

    suspend fun pollDevice(id: String, name: String, status: String, acknowledged: List<String>): List<StudioCommand> = withContext(Dispatchers.IO) {
        val connection = open("/api/devices/poll", "POST")
        try {
            connection.setRequestProperty("Cookie", cookie ?: throw IllegalStateException("Bitte erneut anmelden."))
            connection.setRequestProperty("Origin", BASE)
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.doOutput = true
            val payload = JSONObject().put("id", id).put("name", name.take(80)).put("role", "prompter").put("status", status.take(120)).put("ack", JSONArray(acknowledged))
            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            if (connection.responseCode == 401 || connection.responseCode == 403) throw IllegalStateException("Studio-Sitzung abgelaufen.")
            if (connection.responseCode != 200) throw IllegalStateException("Fernsteuerung nicht erreichbar.")
            val array = JSONObject(connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }).getJSONArray("commands")
            (0 until array.length()).map { i -> array.getJSONObject(i).let { StudioCommand(it.getString("id"), it.getString("type"), it.optString("projectId", ""), it.getLong("remainingMs")) } }
        } finally { connection.disconnect() }
    }

    private fun open(path: String, method: String): HttpURLConnection = (URL(BASE + path).openConnection() as HttpURLConnection).apply {
        requestMethod = method
        connectTimeout = 10000
        readTimeout = 30000
        instanceFollowRedirects = false
        useCaches = false
    }
}

data class StudioProject(val id: String, val title: String, val text: String)
data class StudioCommand(val id: String, val type: String, val projectId: String, val remainingMs: Long)
