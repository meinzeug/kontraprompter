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
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import de.kontrakurs.kontraprompter.video.SavedRecording

/** HTTPS-only, in-memory session. No password or session cookie is persisted. */
class StudioClient {
    companion object { private const val BASE = "https://videomaker.dorfspy.de" }
    private val cookie = SessionCookie()

    suspend fun login(password: String): Unit = withContext(Dispatchers.IO) {
        cookie.set(null)
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
            cookie.set(session)
        } finally { connection.disconnect() }
    }

    suspend fun projects(): List<StudioProject> = withContext(Dispatchers.IO) {
        val connection = open("/api/projects", "GET")
        try {
            val session = cookie.get() ?: throw IllegalStateException("Bitte erneut anmelden.")
            connection.setRequestProperty("Cookie", session)
            if (connection.responseCode == 401) { cookie.invalidate(session); throw IllegalStateException("Sitzung abgelaufen. Bitte erneut anmelden.") }
            if (connection.responseCode != 200) throw IllegalStateException("Skripte konnten nicht geladen werden.")
            val array = JSONArray(connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
            (0 until array.length()).map { i -> array.getJSONObject(i).let { p -> StudioProject(p.getString("id"), p.getString("title"), p.getJSONObject("content").getString("script")) } }
        } finally { connection.disconnect() }
    }

    /** Streams a local file without deleting it; only a matching durable receipt is success. */
    internal suspend fun uploadRecording(recording: SavedRecording, entry: UploadEntry, progress: (Long, Long) -> Unit, workerStarted: () -> Unit, workerFinished: () -> Unit): Unit = suspendCancellableCoroutine { continuation ->
        val active = AtomicReference<HttpURLConnection?>(null)
        val delivered = AtomicBoolean(false)
        continuation.invokeOnCancellation { delivered.set(true); active.get()?.let { connection -> Thread({ runCatching { connection.disconnect() } }, "KontraUploadCancel").start() } }
        val worker = Thread({
            try {
                if (recording.partial) throw UploadException("partial", "Unvollständige Teilvideos können derzeit nur lokal exportiert werden.")
                val session = cookie.get() ?: throw UploadException("auth", "Bitte erneut bei Videomaker anmelden.")
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
                val lastProgress = AtomicLong(android.os.SystemClock.elapsedRealtime())
                val waitingForResponse = AtomicBoolean(false)
                val watchdog = Executors.newSingleThreadScheduledExecutor { action -> Thread(action, "KontraUploadWatchdog") }
                try {
                    if (!continuation.isActive) throw IOException("Upload abgebrochen")
                    watchdog.scheduleAtFixedRate({
                        val limit = if (waitingForResponse.get()) 120_000L else 60_000L
                        if (android.os.SystemClock.elapsedRealtime() - lastProgress.get() > limit && delivered.compareAndSet(false, true)) {
                            if (continuation.isActive) continuation.resumeWithException(UploadException("uncertain", "Upload reagiert nicht. Die lokale Aufnahme bleibt erhalten; ein vorheriger Upload wird noch beendet."))
                            Thread({ runCatching { connection.disconnect() } }, "KontraUploadTimeout").start()
                        }
                    }, 5, 5, TimeUnit.SECONDS)
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
                                sentHash.update(block, 0, count); output.write(block, 0, count)
                                lastProgress.set(android.os.SystemClock.elapsedRealtime())
                                if (!continuation.isActive) throw IOException("Upload abgebrochen")
                                progress(sent, entry.size)
                            }
                        }
                    }
                    check(sent == entry.size && hex(sentHash.digest()) == entry.sha256) { "Lokale Aufnahme wurde während des Uploads verändert." }
                    lastProgress.set(android.os.SystemClock.elapsedRealtime())
                    waitingForResponse.set(true)
                    val code = connection.responseCode
                    if (code !in 200..299) throw uploadFailure(code, session)
                    val text = connection.inputStream.bufferedReader(Charsets.UTF_8).use { input ->
                        val result = StringBuilder(); val block = CharArray(2048)
                        while (true) { val count = input.read(block); if (count < 0) break; check(result.length + count <= 16_384) { "Serverbestätigung ist ungültig." }; result.append(block, 0, count) }
                        result.toString()
                    }
                    val response = JSONObject(text)
                    val rawSize = response.get("sizeBytes")
                    check(rawSize is Int || rawSize is Long) { "Serverbestätigung ist ungültig." }
                    val receipt = UploadReceipt(response.getString("id"), response.getString("projectId"), (rawSize as Number).toLong(), response.getString("sha256"))
                    check(receiptMatches(entry, receipt)) { "Serverbestätigung stimmt nicht mit der Aufnahme überein." }
                    if (delivered.compareAndSet(false, true) && continuation.isActive) continuation.resume(Unit)
                } catch (error: IOException) {
                    if (error is UploadException) throw error
                    val status = runCatching { connection.responseCode }.getOrNull()
                    if (status != null && status !in 200..299) throw uploadFailure(status, session)
                    throw error
                } finally { watchdog.shutdownNow(); active.compareAndSet(connection, null); connection.disconnect() }
            } catch (error: Exception) { if (delivered.compareAndSet(false, true) && continuation.isActive) continuation.resumeWithException(error) }
            finally { workerFinished() }
        }, "KontraStudioUpload")
        workerStarted()
        try { worker.start() } catch (error: Exception) { workerFinished(); if (delivered.compareAndSet(false, true) && continuation.isActive) continuation.resumeWithException(error) }
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun uploadFailure(status: Int, session: String): UploadException = when (status) {
        401, 403 -> { cookie.invalidate(session); UploadException("auth", "Sitzung abgelaufen. Bitte erneut anmelden.") }
        404 -> UploadException("project_missing", "Skript nicht gefunden oder Upload-Endpunkt nicht verfügbar. Die Aufnahme bleibt lokal.")
        409 -> UploadException("conflict", "Upload-ID oder gespeicherte Datei stimmt nicht überein. Die Aufnahme bleibt lokal.")
        413 -> UploadException("size", "Die Aufnahme überschreitet das Serverlimit. Die Aufnahme bleibt lokal.")
        429 -> UploadException("busy", "Der Server bearbeitet bereits andere Uploads. Bitte später erneut versuchen.")
        507 -> UploadException("storage", "Auf dem Server ist nicht genug Speicher frei. Die Aufnahme bleibt lokal.")
        else -> UploadException("uncertain", "Upload nicht bestätigt (HTTP $status). Die Aufnahme bleibt lokal.")
    }

    suspend fun pollDevice(id: String, name: String, status: String, acknowledged: List<String>): List<StudioCommand> = withContext(Dispatchers.IO) {
        val connection = open("/api/devices/poll", "POST")
        try {
            connection.setRequestProperty("Cookie", cookie.get() ?: throw IllegalStateException("Bitte erneut anmelden."))
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
