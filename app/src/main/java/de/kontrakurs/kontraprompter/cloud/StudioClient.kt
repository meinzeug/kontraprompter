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
import de.kontrakurs.kontraprompter.camera.CameraCommand
import de.kontrakurs.kontraprompter.camera.CameraPollRequest
import de.kontrakurs.kontraprompter.camera.CameraPollResponse
import de.kontrakurs.kontraprompter.camera.CameraDeviceIdentity
import android.util.Base64
import de.kontrakurs.kontraprompter.GenerationNotice
import de.kontrakurs.kontraprompter.GenerationNoticeStatus
import de.kontrakurs.kontraprompter.parseGenerationNotice
import de.kontrakurs.kontraprompter.requireProjectString

/** HTTPS-only, in-memory session. No password or session cookie is persisted. */
class StudioClient {
    companion object { private const val BASE = "https://videomaker.dorfspy.de" }
    private val cookie = SessionCookie()

    /** A short-lived proof is kept only in memory and bound to the current studio login. */
    internal class CameraDeviceTicket internal constructor(internal val deviceId: String, internal val value: String, internal val studioSession: String, internal val expiresAt: Long)

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
            val projects = (0 until array.length()).map { i -> array.getJSONObject(i).let { p ->
                val content = p.getJSONObject("content")
                val raw = content.opt("generation_warnings")
                val values = if (raw is JSONArray) (0 until minOf(raw.length(), 11)).map { raw.opt(it) } else raw
                val id = requireProjectString(p.opt("id"))
                StudioProject(id, requireProjectString(p.opt("title")), requireProjectString(content.opt("script")), parseGenerationNotice(content.has("generation_warnings"), values))
            } }
            check(cookie.get() == session) { "Studio-Sitzung hat sich geändert. Bitte erneut laden." }
            projects
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
                check(entry.cameraRecordingId == if (recording.projectId == entry.projectId) recording.id else null)
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
                    entry.cameraRecordingId?.let { connection.setRequestProperty("X-Camera-Recording-Id", it) }
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
                    if (code !in 200..299) throw uploadFailure(code, session, connection, entry.cameraRecordingId != null)
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
                    if (status != null && status !in 200..299) throw uploadFailure(status, session, connection, entry.cameraRecordingId != null)
                    throw error
                } finally { watchdog.shutdownNow(); active.compareAndSet(connection, null); connection.disconnect() }
            } catch (error: Exception) { if (delivered.compareAndSet(false, true) && continuation.isActive) continuation.resumeWithException(error) }
            finally { workerFinished() }
        }, "KontraStudioUpload")
        workerStarted()
        try { worker.start() } catch (error: Exception) { workerFinished(); if (delivered.compareAndSet(false, true) && continuation.isActive) continuation.resumeWithException(error) }
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun uploadFailure(status: Int, session: String, connection: HttpURLConnection, cameraClaim: Boolean): UploadException = when (status) {
        401, 403 -> { cookie.invalidate(session); UploadException("auth", "Sitzung abgelaufen. Bitte erneut anmelden.") }
        404 -> UploadException("project_missing", "Skript nicht gefunden oder Upload-Endpunkt nicht verfügbar. Die Aufnahme bleibt lokal.")
        409 -> {
            val code = runCatching {
                val input = connection.errorStream ?: return@runCatching null
                val text = input.bufferedReader(Charsets.UTF_8).use { reader ->
                    val result = StringBuilder(); val block = CharArray(512)
                    while (true) { val n = reader.read(block); if (n < 0) break; check(result.length + n <= 4096); result.append(block, 0, n) }
                    result.toString()
                }
                JSONObject(text).optString("code", "")
            }.getOrNull()
            when (val kind = cameraUploadConflictKind(code, cameraClaim)) {
                "manifest_pending" -> UploadException(kind, "Der Gerätenachweis fehlt noch. Die Aufnahme bleibt lokal und wird erneut gemeldet.")
                "manifest_mismatch" -> UploadException(kind, "Der Gerätenachweis stimmt nicht mit der Aufnahme überein. Das Original bleibt lokal.")
                "manifest_partial" -> UploadException(kind, "Das Teilvideo kann derzeit nur lokal exportiert werden.")
                "busy" -> UploadException(kind, "Ein vorheriger Upload wird noch bearbeitet. Die Aufnahme bleibt lokal; später wird erneut versucht.")
                else -> UploadException(kind, "Upload-ID oder gespeicherte Datei stimmt nicht überein. Die Aufnahme bleibt lokal.")
            }
        }
        413 -> UploadException("size", "Die Aufnahme überschreitet das Serverlimit. Die Aufnahme bleibt lokal.")
        429 -> UploadException("busy", "Der Server bearbeitet bereits andere Uploads. Bitte später erneut versuchen.")
        507 -> UploadException("storage", "Auf dem Server ist nicht genug Speicher frei. Die Aufnahme bleibt lokal.")
        else -> UploadException("uncertain", "Upload nicht bestätigt (HTTP $status). Die Aufnahme bleibt lokal.")
    }

    /** v1 fixture transport only. Production activation requires the v2 device identity contract. */
    internal fun pollCameraFixture(sessionId: String, request: CameraPollRequest): CameraPollResponse = pollCamera(sessionId, request, null)

    internal fun pollCameraDevice(ticket: CameraDeviceTicket, request: CameraPollRequest): CameraPollResponse {
        check(cookie.get() == ticket.studioSession) { "Studio-Sitzung hat sich geändert. Bitte erneut verbinden." }
        if (android.os.SystemClock.elapsedRealtime() >= ticket.expiresAt) throw CameraDeviceTicketException("Kameraticket abgelaufen. Geräteanmeldung wird erneuert.")
        return pollCamera(ticket.deviceId, request, ticket)
    }

    /** Ephemeral preview signaling; it shares only the explicitly authenticated device ticket. */
    internal fun pollCameraPreview(ticket: CameraDeviceTicket, payload: JSONObject): JSONObject {
        check(cookie.get() == ticket.studioSession) { "Studio-Sitzung hat sich geändert." }
        if (android.os.SystemClock.elapsedRealtime() >= ticket.expiresAt) throw CameraDeviceTicketException("Kameraticket abgelaufen.")
        val body = payload.toString().toByteArray(Charsets.UTF_8)
        check(body.size <= 96 * 1024)
        val connection = open("/api/camera-preview/device", "POST")
        try {
            connection.connectTimeout = 3000; connection.readTimeout = 3000
            connection.setRequestProperty("Cookie", ticket.studioSession)
            connection.setRequestProperty("X-Camera-Device-Ticket", ticket.value)
            connection.setRequestProperty("Origin", BASE)
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.doOutput = true; connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
            val code = connection.responseCode
            if (code == 403) throw CameraDeviceTicketException("Kameraticket nicht verfügbar.")
            if (code != 200) throw IOException("Webvorschau nicht verfügbar (HTTP $code).")
            val text = connection.inputStream.bufferedReader(Charsets.UTF_8).use { input ->
                val result = StringBuilder(); val block = CharArray(2048)
                while (true) { val count = input.read(block); if (count < 0) break; check(result.length + count <= 96 * 1024); result.append(block, 0, count) }
                result.toString()
            }
            check(cookie.get() == ticket.studioSession) { "Studio-Sitzung hat sich geändert." }
            return JSONObject(text)
        } finally { connection.disconnect() }
    }

    private fun pollCamera(sessionId: String, request: CameraPollRequest, ticket: CameraDeviceTicket?): CameraPollResponse {
        require(UUID.fromString(sessionId).toString() == sessionId)
        val session = cookie.get() ?: throw IllegalStateException("Bitte erneut anmelden.")
        val connection = open("/api/camera-devices/poll", "POST")
        try {
            val ack = JSONArray().apply { request.acknowledgements.forEach { put(JSONObject().put("id", it.id).put("result", it.result)) } }
            val name = android.os.Build.MODEL.filter { it.code >= 32 && it.code != 127 }.take(80).ifBlank { "Android-Kamera" }
            val body = JSONObject().put("id", sessionId).put("sequence", request.sequence).put("name", name)
                .put("capability", "remote-record-v1").put("phase", request.report.phase)
                .put("recordingId", request.report.recordingId ?: JSONObject.NULL).put("projectId", request.report.projectId ?: JSONObject.NULL)
                .put("sizeBytes", request.report.sizeBytes ?: JSONObject.NULL).put("sha256", request.report.sha256 ?: JSONObject.NULL).put("ack", ack)
                .toString().toByteArray(Charsets.UTF_8)
            check(body.size <= 8192)
            connection.setRequestProperty("Cookie", session)
            if (ticket != null) {
                check(session == ticket.studioSession)
                connection.setRequestProperty("X-Camera-Device-Ticket", ticket.value)
            }
            connection.setRequestProperty("Origin", BASE)
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
            val code = connection.responseCode
            if (code == 403 && ticket != null) throw CameraDeviceTicketException("Kameraticket abgelaufen. Geräteanmeldung wird erneuert.")
            if (code == 401 || code == 403) { cookie.invalidate(session); throw IllegalStateException("Sitzung abgelaufen. Bitte erneut anmelden.") }
            if (code == 429 || code in 500..599) throw IOException("Kamerafernsteuerung ist vorübergehend nicht erreichbar (HTTP $code).")
            check(code == 200) { "Kamerafernsteuerung ist nicht erreichbar (HTTP $code)." }
            val text = connection.inputStream.bufferedReader(Charsets.UTF_8).use { input ->
                val result = StringBuilder(); val block = CharArray(1024)
                while (true) { val count = input.read(block); if (count < 0) break; check(result.length + count <= 16_384); result.append(block, 0, count) }
                result.toString()
            }
            val commands = JSONObject(text).getJSONArray("commands")
            check(cookie.get() == session) { "Studio-Sitzung hat sich geändert." }
            check(commands.length() <= 1)
            if (commands.length() == 0) return CameraPollResponse(null, 0)
            val item = commands.getJSONObject(0)
            val project = if (item.isNull("projectId")) null else item.getString("projectId")
            val command = CameraCommand(item.getString("id"), item.getString("type"), item.getString("recordingId"), project)
            val remaining = item.getLong("remainingMs"); check(remaining in 0..10_000)
            return CameraPollResponse(command, remaining)
        } finally { connection.disconnect() }
    }

    internal suspend fun registerCameraDevice(identity: CameraDeviceIdentity): Unit = withContext(Dispatchers.IO) {
        val session = cookie.get() ?: throw IllegalStateException("Bitte erneut anmelden.")
        val answer = cameraProof(identity, "register", session)
        check(answer.optBoolean("registered", false)) { "Kameraregistrierung nicht bestätigt." }
    }

    internal suspend fun startCameraDeviceSession(identity: CameraDeviceIdentity): CameraDeviceTicket = withContext(Dispatchers.IO) {
        val session = cookie.get() ?: throw IllegalStateException("Bitte erneut anmelden.")
        val started = android.os.SystemClock.elapsedRealtime()
        val answer = cameraProof(identity, "session", session)
        val value = answer.getString("ticket"); val duration = answer.getLong("expiresInMs")
        check(value.matches(Regex("^[A-Za-z0-9_-]{16,1024}$")) && duration in 1..3_600_000)
        check(cookie.get() == session)
        val expiresAt = started + duration
        check(android.os.SystemClock.elapsedRealtime() < expiresAt) { "Kamerasitzung bereits abgelaufen." }
        CameraDeviceTicket(identity.id, value, session, expiresAt)
    }

    private fun cameraProof(identity: CameraDeviceIdentity, action: String, session: String): JSONObject {
        check(cookie.get() == session)
        val started = android.os.SystemClock.elapsedRealtime()
        val challenge = try { cameraJsonPost("/api/camera-devices/pairing/challenge", session, JSONObject().put("deviceId", identity.id).put("action", action)) }
        catch (error: CameraProtocolConflictException) { if (action == "session") throw CameraRegistrationRequiredException() else throw error }
        val challengeId = challenge.getString("challengeId"); val nonce = challenge.getString("challenge")
        val duration = challenge.getLong("expiresInMs"); check(duration in 1..60_000)
        val payload = if (action == "register") identity.publicKeyDer else ByteArray(0)
        val digest = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val signature = identity.signChallenge(challengeId, nonce, action, digest)
        val encode = { value: ByteArray -> Base64.encodeToString(value, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP) }
        check(cookie.get() == session && android.os.SystemClock.elapsedRealtime() - started < duration) { "Kameraanmeldung abgelaufen. Bitte erneut versuchen." }
        return cameraJsonPost("/api/camera-devices/pairing/complete", session, JSONObject().put("deviceId", identity.id).put("action", action)
            .put("challengeId", challengeId).put("payload", encode(payload)).put("signature", encode(signature)))
    }

    private fun cameraJsonPost(path: String, session: String, body: JSONObject): JSONObject {
        check(cookie.get() == session) { "Studio-Sitzung hat sich geändert." }
        val connection = open(path, "POST")
        try {
            val bytes = body.toString().toByteArray(Charsets.UTF_8); check(bytes.size <= 8192)
            connection.setRequestProperty("Cookie", session); connection.setRequestProperty("Origin", BASE)
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.doOutput = true; connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }
            val status = connection.responseCode
            if (status == 401 || status == 403) { cookie.invalidate(session); throw IllegalStateException("Sitzung abgelaufen. Bitte erneut anmelden.") }
            if (status == 409) throw CameraProtocolConflictException("Kameragerät konnte nicht zugeordnet werden (HTTP 409).")
            if (status == 429 || status in 500..599) throw IOException("Kameraanmeldung ist vorübergehend nicht erreichbar (HTTP $status).")
            check(status == 200 || status == 201) { "Kameraanmeldung fehlgeschlagen (HTTP $status)." }
            val text = connection.inputStream.bufferedReader(Charsets.UTF_8).use { input ->
                val result = StringBuilder(); val block = CharArray(1024)
                while (true) { val count = input.read(block); if (count < 0) break; check(result.length + count <= 16_384); result.append(block, 0, count) }
                result.toString()
            }
            check(cookie.get() == session) { "Studio-Sitzung hat sich geändert." }
            return JSONObject(text)
        } finally { connection.disconnect() }
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

data class StudioProject(val id: String, val title: String, val text: String, val generationNotice: GenerationNotice = GenerationNotice(GenerationNoticeStatus.UNKNOWN))
data class StudioCommand(val id: String, val type: String, val projectId: String, val remainingMs: Long)
internal class UploadException(val kind: String, message: String) : IOException(message)
internal class CameraDeviceTicketException(message: String) : IOException(message)
internal class CameraProtocolConflictException(message: String) : IOException(message)
internal class CameraRegistrationRequiredException : IOException("Dieses Kameragerät ist noch nicht registriert.")
