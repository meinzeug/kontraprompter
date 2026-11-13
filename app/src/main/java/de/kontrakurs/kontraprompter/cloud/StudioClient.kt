package de.kontrakurs.kontraprompter.cloud

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import de.kontrakurs.kontraprompter.video.SavedRecording

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

    /** Streams a local file without deleting it; only a matching durable receipt is success. */
    internal suspend fun uploadRecording(recording: SavedRecording, entry: UploadEntry, progress: (Long, Long) -> Unit, workerFinished: () -> Unit): Unit = suspendCancellableCoroutine { continuation ->
        val active = AtomicReference<HttpURLConnection?>(null)
        continuation.invokeOnCancellation { active.get()?.disconnect() }
        Thread({
            try {
                val session = cookie ?: throw UploadException("auth", "Bitte erneut bei Videomaker anmelden.")
                check(recording.bytes in 1..(1024L * 1024 * 1024) && recording.file.isFile && recording.file.length() == recording.bytes) { "Lokale Aufnahme ist nicht vollständig vorhanden." }
                check(entry.recordingId == recording.id && entry.size == recording.bytes && entry.sha256 == recording.sha256)
                check(UUID.fromString(entry.projectId).toString() == entry.projectId && UUID.fromString(entry.uploadId).toString() == entry.uploadId)
                val before = MessageDigest.getInstance("SHA-256")
                recording.file.inputStream().buffered().use { source ->
                    val block = ByteArray(256 * 1024)
                    while (true) { if (!continuation.isActive) throw IOException("Upload abgebrochen"); val count = source.read(block); if (count < 0) break; before.update(block, 0, count) }
                }
                check(hex(before.digest()) == recording.sha256) { "Lokale Aufnahme wurde verändert und wird nicht hochgeladen." }
                val connection = open("/api/projects/${entry.projectId}/recordings", "POST").also { active.set(it) }
                try {
                    if (!continuation.isActive) throw IOException("Upload abgebrochen")
                    connection.readTimeout = 120_000
                    connection.setRequestProperty("Cookie", session)
                    connection.setRequestProperty("Origin", BASE)
                    connection.setRequestProperty("Content-Type", "video/mp4")
                    connection.setRequestProperty("X-Recording-Id", entry.uploadId)
                    connection.setRequestProperty("X-Content-SHA256", entry.sha256)
                    connection.doOutput = true
                    connection.setFixedLengthStreamingMode(entry.size)
                    val sentHash = MessageDigest.getInstance("SHA-256")
                    var sent = 0L
                    recording.file.inputStream().buffered().use { source ->
                        connection.outputStream.use { output ->
                            val block = ByteArray(256 * 1024)
                            while (true) {
                                if (!continuation.isActive) throw IOException("Upload abgebrochen")
                                val count = source.read(block); if (count < 0) break
                                sent += count; check(sent <= entry.size) { "Lokale Aufnahme wurde verändert." }
                                sentHash.update(block, 0, count); output.write(block, 0, count); progress(sent, entry.size)
                            }
                        }
                    }
                    check(sent == entry.size && hex(sentHash.digest()) == entry.sha256) { "Lokale Aufnahme wurde während des Uploads verändert." }
                    val code = connection.responseCode
                    if (code == 401 || code == 403) { cookie = null; throw UploadException("auth", "Sitzung abgelaufen. Bitte erneut anmelden.") }
                    if (code == 404) throw UploadException("project_missing", "Skript nicht gefunden oder Upload-Endpunkt nicht verfügbar. Die Aufnahme bleibt lokal.")
                    if (code == 409) throw UploadException("conflict", "Upload-ID oder gespeicherte Datei stimmt nicht überein. Die Aufnahme bleibt lokal.")
                    if (code !in 200..299) throw UploadException("uncertain", "Upload nicht bestätigt (HTTP $code). Die Aufnahme bleibt lokal.")
                    val text = connection.inputStream.bufferedReader(Charsets.UTF_8).use { input ->
                        val result = StringBuilder(); val block = CharArray(2048)
                        while (true) { val count = input.read(block); if (count < 0) break; check(result.length + count <= 16_384) { "Serverbestätigung ist ungültig." }; result.append(block, 0, count) }
                        result.toString()
                    }
                    val response = JSONObject(text)
                    check(UUID.fromString(response.getString("id")).toString() == entry.uploadId &&
                        UUID.fromString(response.getString("projectId")).toString() == entry.projectId &&
                        response.getLong("sizeBytes") == entry.size && response.getString("sha256") == entry.sha256) { "Serverbestätigung stimmt nicht mit der Aufnahme überein." }
                    if (continuation.isActive) continuation.resume(Unit)
                } finally { active.compareAndSet(connection, null); connection.disconnect() }
            } catch (error: Exception) { if (continuation.isActive) continuation.resumeWithException(error) }
            finally { workerFinished() }
        }, "KontraStudioUpload").start()
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }

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
internal class UploadException(val kind: String, message: String) : IOException(message)
