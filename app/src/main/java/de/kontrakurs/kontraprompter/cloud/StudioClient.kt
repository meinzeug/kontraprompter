package de.kontrakurs.kontraprompter.cloud

import kotlinx.coroutines.Dispatchers
import de.kontrakurs.kontraprompter.video.cameraClaimRecordingId
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
import de.kontrakurs.kontraprompter.camera.CameraLateReportConflictException
import de.kontrakurs.kontraprompter.camera.matchesLateReportConflict
import de.kontrakurs.kontraprompter.camera.CameraDeviceIdentity
import android.util.Base64
import de.kontrakurs.kontraprompter.GenerationNotice
import de.kontrakurs.kontraprompter.GenerationNoticeStatus
import de.kontrakurs.kontraprompter.parseGenerationNotice
import de.kontrakurs.kontraprompter.requireProjectString

/** HTTPS-only, in-memory session. No password or session cookie is persisted. */
class StudioClient private constructor(internal val cookie: SessionCookie) {
    constructor() : this(SessionCookie())
    internal constructor(registry: SessionRegistry, owner: SessionOwner) : this(registry.slot(owner))
    companion object { private const val BASE = "https://videomaker.dorfspy.de" }

    /** Reports only whether this app process currently holds a studio session. */
    fun hasSession(): Boolean = cookie.get() != null

    /** Kept only for the duration of one manual continuation attempt. */
    internal class ManualUploadSession internal constructor(internal val snapshot: SessionCookie.Snapshot) {
        internal val cookie: String get() = snapshot.value
    }
    internal fun manualUploadSession(): ManualUploadSession? = cookie.get()?.let(::ManualUploadSession)
    internal fun manualUploadSessionMatches(session: ManualUploadSession): Boolean = cookie.isCurrent(session.snapshot)
    /** Random bearer sessions are bound only by a one-way, domain-separated digest. */
    internal fun manualUploadSessionBinding(session: ManualUploadSession): String =
        hex(MessageDigest.getInstance("SHA-256").digest(("kontraprompter-manual-upload-session-v1\u0000" + session.cookie).toByteArray(Charsets.UTF_8)))

    /** A short-lived proof is kept only in memory and bound to the current studio login. */
    internal class CameraDeviceTicket internal constructor(internal val deviceId: String, internal val value: String, internal val studioSession: SessionCookie.Snapshot, internal val expiresAt: Long)

    suspend fun login(password: String): Unit = withContext(Dispatchers.IO) {
        val attempt = cookie.beginLogin()
        val connection = try { open("/api/login", "POST") }
            catch (error: Exception) { cookie.abandon(attempt); throw error }
        try {
            connection.doOutput = true
            connection.setRequestProperty("Origin", BASE)
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.outputStream.use { it.write(JSONObject().put("password", password).toString().toByteArray(Charsets.UTF_8)) }
            if (connection.responseCode != 200) throw IllegalStateException(if (connection.responseCode == 429) "Zu viele Anmeldeversuche. Bitte später erneut versuchen." else "Anmeldung fehlgeschlagen. Passwort und Verbindung prüfen.")
            val session = connection.headerFields.entries.filter { it.key.equals("Set-Cookie", true) }
                .flatMap { it.value }.map { it.substringBefore(';') }.firstOrNull { it.startsWith("kontrakurs_session=") }
                ?: throw IllegalStateException("Der Server hat keine Sitzung eingerichtet.")
            check(cookie.complete(attempt, session)) { "Anmeldung durch neuere Aktion ersetzt." }
        } finally { cookie.abandon(attempt); connection.disconnect() }
    }

    suspend fun projects(): List<StudioProject> = withContext(Dispatchers.IO) {
        val connection = open("/api/projects", "GET")
        try {
            val session = cookie.get() ?: throw IllegalStateException("Bitte erneut anmelden.")
            connection.setRequestProperty("Cookie", session.value)
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

    /** Idempotent server association for a LAN take without a cloud script; no script text crosses LAN. */
    internal suspend fun ensureLanRecordingProject(recordingId: String, requiredSession: ManualUploadSession): String = withContext(Dispatchers.IO) {
        val id = UUID.fromString(recordingId).toString()
        require(id == recordingId && manualUploadSessionMatches(requiredSession)) { "Studio-Sitzung hat sich geändert." }
        val connection = open("/api/projects/lan-captures", "POST")
        try {
            connection.setRequestProperty("Cookie", requiredSession.cookie)
            connection.setRequestProperty("Origin", BASE)
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            val payload = JSONObject().put("recordingId", id).toString().toByteArray(Charsets.UTF_8)
            connection.doOutput = true; connection.setFixedLengthStreamingMode(payload.size)
            connection.outputStream.use { it.write(payload) }
            val code = connection.responseCode
            if (code == 401 || code == 403) { cookie.invalidate(requiredSession.snapshot); throw UploadException("auth", "Studio-Sitzung abgelaufen. Bitte erneut anmelden.") }
            if (code == 409) throw UploadException("conflict", "Das Aufnahmeprojekt kann nicht eindeutig zugeordnet werden. Das Original bleibt lokal.")
            if (code !in setOf(200, 201)) throw UploadException("uncertain", "Aufnahmeprojekt nicht bestätigt (HTTP $code). Das Original bleibt lokal.")
            val text = connection.inputStream.bufferedReader(Charsets.UTF_8).use { input ->
                val result = StringBuilder(); val block = CharArray(1024)
                while (true) { val n = input.read(block); if (n < 0) break; check(result.length + n <= 16_384); result.append(block, 0, n) }
                result.toString()
            }
            val response = JSONObject(text)
            check(response.getString("recordingId") == id) { "Aufnahmezuordnung stimmt nicht." }
            val project = response.getString("projectId")
            check(UUID.fromString(project).toString() == project && manualUploadSessionMatches(requiredSession)) { "Projekt oder Studio-Sitzung stimmt nicht." }
            project
        } finally { connection.disconnect() }
    }

    /** Streams a local file without deleting it; only a matching durable receipt is success. */
    internal suspend fun uploadRecording(recording: SavedRecording, entry: UploadEntry, progress: (Long, Long) -> Unit, workerStarted: () -> Unit, workerFinished: () -> Unit, requiredSession: ManualUploadSession? = null): Unit = suspendCancellableCoroutine { continuation ->
        val active = AtomicReference<HttpURLConnection?>(null)
        val delivered = AtomicBoolean(false)
        continuation.invokeOnCancellation { delivered.set(true); active.get()?.let { connection -> Thread({ runCatching { connection.disconnect() } }, "KontraUploadCancel").start() } }
        val worker = Thread({
            try {
                if (recording.partial) throw UploadException("partial", "Unvollständige Teilvideos können derzeit nur lokal exportiert werden.")
                val session = cookie.get() ?: throw UploadException("auth", "Bitte erneut bei Videomaker anmelden.")
                if (requiredSession != null && requiredSession.snapshot !== session) throw UploadException("auth", "Die Studio-Sitzung hat sich geändert. Bitte erneut anmelden.")
                fun ensureSession() {
                    if (cookie.get() != session) throw UploadException("auth", "Studio-Sitzung geändert; Serverbeleg prüfen. Die Aufnahme bleibt lokal.")
                }
                if (recording.bytes !in 1..(1024L * 1024 * 1024) || !recording.file.isFile || recording.file.length() != recording.bytes)
                    throw UploadException("file_changed", "Lokale Aufnahme ist nicht vollständig vorhanden. Das Original bleibt lokal.")
                check(entry.recordingId == recording.id && entry.size == recording.bytes && entry.sha256 == recording.sha256)
                check(entry.cameraRecordingId == cameraClaimRecordingId(recording.origin, recording.id, recording.projectId, entry.projectId))
                check(UUID.fromString(entry.projectId).toString() == entry.projectId && UUID.fromString(entry.uploadId).toString() == entry.uploadId)
                val before = MessageDigest.getInstance("SHA-256")
                recording.file.inputStream().buffered().use { source ->
                    val block = ByteArray(256 * 1024)
                    while (true) { if (!continuation.isActive) throw IOException("Upload abgebrochen"); val count = source.read(block); if (count < 0) break; before.update(block, 0, count) }
                }
                if (hex(before.digest()) != recording.sha256) throw UploadException("file_changed", "Lokale Aufnahme wurde verändert und wird nicht hochgeladen.")
                ensureSession()
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
                    connection.setRequestProperty("Cookie", session.value)
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
                                ensureSession()
                                val count = source.read(block); if (count < 0) break
                                sent += count
                                if (sent > entry.size) throw UploadException("file_changed", "Lokale Aufnahme wurde während des Uploads verändert.")
                                sentHash.update(block, 0, count); output.write(block, 0, count)
                                ensureSession()
                                lastProgress.set(android.os.SystemClock.elapsedRealtime())
                                if (!continuation.isActive) throw IOException("Upload abgebrochen")
                                progress(sent, entry.size)
                            }
                        }
                    }
                    if (sent != entry.size || hex(sentHash.digest()) != entry.sha256) throw UploadException("file_changed", "Lokale Aufnahme wurde während des Uploads verändert.")
                    lastProgress.set(android.os.SystemClock.elapsedRealtime())
                    waitingForResponse.set(true)
                    val code = connection.responseCode
                    ensureSession()
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
                    // The later coordinator journal write still needs its own logout fence.
                    ensureSession()
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

    private fun uploadFailure(status: Int, session: SessionCookie.Snapshot, connection: HttpURLConnection, cameraClaim: Boolean): UploadException = when (status) {
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
            connection.setRequestProperty("Cookie", ticket.studioSession.value)
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
                .put("capability", "remote-record-v2").put("phase", request.report.phase)
                .put("paused", request.report.paused ?: JSONObject.NULL)
                .put("cameraFront", request.report.cameraFront ?: JSONObject.NULL).put("cameraCanSwitch", request.report.cameraCanSwitch)
                .put("recordingId", request.report.recordingId ?: JSONObject.NULL).put("projectId", request.report.projectId ?: JSONObject.NULL)
                .put("sizeBytes", request.report.sizeBytes ?: JSONObject.NULL).put("sha256", request.report.sha256 ?: JSONObject.NULL).put("ack", ack)
                .toString().toByteArray(Charsets.UTF_8)
            check(body.size <= 8192)
            connection.setRequestProperty("Cookie", session.value)
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
            if (code == 409 && ticket != null && isLateReportConflict(connection, sessionId, request)) {
                check(cookie.get() == session) { "Studio-Sitzung hat sich geändert. Bitte erneut verbinden." }
                throw CameraLateReportConflictException(sessionId)
            }
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
            val from = if (!item.has("cameraFrom") || item.isNull("cameraFrom")) null else item.getBoolean("cameraFrom")
            val command = CameraCommand(item.getString("id"), item.getString("type"), item.getString("recordingId"), project, from)
            val remaining = item.getLong("remainingMs"); check(remaining in 0..10_000)
            return CameraPollResponse(command, remaining)
        } finally { connection.disconnect() }
    }

    private fun isLateReportConflict(connection: HttpURLConnection, deviceId: String, request: CameraPollRequest): Boolean {
        val stream = connection.errorStream ?: return false
        val text = stream.bufferedReader(Charsets.UTF_8).use { input ->
            val result = StringBuilder(); val block = CharArray(1024)
            while (true) { val count = input.read(block); if (count < 0) break; check(result.length + count <= 4096); result.append(block, 0, count) }
            result.toString()
        }
        val data = try { JSONObject(text) } catch (_: org.json.JSONException) { return false }
        val names = listOf("code", "deviceId", "recordingId", "projectId", "phase", "sequence", "sizeBytes", "sha256")
        if (names.any { !data.has(it) }) return false
        fun field(name: String): Any? = if (data.isNull(name)) null else data.opt(name)
        val fields = names.associateWith(::field)
        return matchesLateReportConflict(deviceId, request, fields)
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

    private fun cameraProof(identity: CameraDeviceIdentity, action: String, session: SessionCookie.Snapshot): JSONObject {
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

    private fun cameraJsonPost(path: String, session: SessionCookie.Snapshot, body: JSONObject): JSONObject {
        check(cookie.get() == session) { "Studio-Sitzung hat sich geändert." }
        val connection = open(path, "POST")
        try {
            val bytes = body.toString().toByteArray(Charsets.UTF_8); check(bytes.size <= 8192)
            connection.setRequestProperty("Cookie", session.value); connection.setRequestProperty("Origin", BASE)
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

    suspend fun pollDevice(id: String, name: String, status: String, acknowledged: List<String>, speed: Float, fontSize: Float): List<StudioCommand> = withContext(Dispatchers.IO) {
        require(speed.isFinite() && speed in 5f..160f && fontSize.isFinite() && fontSize in 20f..100f)
        val session = cookie.get() ?: throw IllegalStateException("Bitte erneut anmelden.")
        val connection = open("/api/devices/poll", "POST")
        try {
            connection.setRequestProperty("Cookie", session.value)
            connection.setRequestProperty("Origin", BASE)
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.doOutput = true
            val payload = JSONObject().put("id", id).put("name", name.take(80)).put("role", "prompter").put("status", status.take(120)).put("ack", JSONArray(acknowledged))
                .put("controls", JSONObject().put("speed", speed.toDouble()).put("fontSize", fontSize.toDouble()))
            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            if (connection.responseCode == 401 || connection.responseCode == 403) { cookie.invalidate(session); throw IllegalStateException("Studio-Sitzung abgelaufen.") }
            if (connection.responseCode != 200) throw IllegalStateException("Fernsteuerung nicht erreichbar.")
            val text = connection.inputStream.bufferedReader(Charsets.UTF_8).use { input ->
                val result = StringBuilder(); val block = CharArray(1024)
                while (true) { val count = input.read(block); if (count < 0) break; check(result.length + count <= 16_384); result.append(block, 0, count) }
                result.toString()
            }
            check(cookie.get() == session) { "Studio-Sitzung hat sich geändert." }
            val array = JSONObject(text).getJSONArray("commands"); check(array.length() <= 1)
            (0 until array.length()).map { i -> array.getJSONObject(i).let { item ->
                val raw = if (item.isNull("value")) null else item.get("value")
                check(raw == null || raw is Number) { "Ungültiger Fernsteuerungswert." }
                val id = item.getString("id"); val type = item.getString("type"); val remaining = item.getLong("remainingMs")
                check(runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false) && remaining in 0..10_000)
                check(type in setOf("load", "play", "pause", "reset", "set_speed", "set_font_size"))
                StudioCommand(id, type, item.optString("projectId", ""), remaining, (raw as? Number)?.toDouble())
            } }
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
data class StudioCommand(val id: String, val type: String, val projectId: String, val remainingMs: Long, val value: Double? = null)
internal class UploadException(val kind: String, message: String) : IOException(message)
internal class CameraDeviceTicketException(message: String) : IOException(message)
internal class CameraProtocolConflictException(message: String) : IOException(message)
internal class CameraRegistrationRequiredException : IOException("Dieses Kameragerät ist noch nicht registriert.")
