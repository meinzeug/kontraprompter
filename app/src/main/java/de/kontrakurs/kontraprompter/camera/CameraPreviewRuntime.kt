package de.kontrakurs.kontraprompter.camera

import android.os.SystemClock
import de.kontrakurs.kontraprompter.cloud.StudioClient
import de.kontrakurs.kontraprompter.video.LiveSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Separate, explicitly enabled video-only session. It never controls the recorder or primary peer. */
internal class CameraPreviewRuntime(
    private val session: LiveSession,
    private val client: StudioClient,
    private val ticket: () -> StudioClient.CameraDeviceTicket?,
) : AutoCloseable {
    private val lock = Any()
    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled
    private val _status = MutableStateFlow("Webvorschau aus")
    val status: StateFlow<String> = _status
    @Volatile private var activeId: String? = null
    @Volatile private var deadline = 0L
    @Volatile private var worker: Thread? = null
    @Volatile private var closed = false
    private val pending = mutableListOf<JSONObject>()
    private var after = 0L
    private val watchdog = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "preview-lease").apply { isDaemon = true } }

    init {
        watchdog.scheduleAtFixedRate({
            val observedId = activeId
            val observedDeadline = deadline
            val now = SystemClock.elapsedRealtime()
            if (observedId != null && now >= observedDeadline) expire(observedId, observedDeadline, now)
        }, 250, 250, TimeUnit.MILLISECONDS)
    }

    private fun expire(id: String, observedDeadline: Long, now: Long) {
        val expired = synchronized(lock) {
            if (!previewLeaseMayExpire(id, observedDeadline, activeId, deadline, now)) false
            else {
                _enabled.value = false; _status.value = "Webvorschau beendet · Verbindung abgelaufen"
                activeId = null; deadline = 0; pending.clear(); after = 0
                true
            }
        }
        if (expired) session.stopBrowserPreview(id)
    }

    fun enable() = synchronized(lock) {
        if (closed || _enabled.value) return@synchronized
        if (worker?.isAlive == true) { _status.value = "Webvorschau wird noch getrennt"; return@synchronized }
        if (!session.browserPreviewAvailable()) { _status.value = "Kamera ist für die Webvorschau noch nicht bereit"; return@synchronized }
        pending.clear(); after = 0; activeId = null; deadline = 0
        _enabled.value = true; _status.value = "Webvorschau freigegeben · wartet auf Dashboard"
        worker = Thread({ run() }, "preview-signaling").apply { isDaemon = true; start() }
    }

    fun disable(message: String = "Webvorschau aus") {
        val id = synchronized(lock) {
            _enabled.value = false; _status.value = message
            val old = activeId; activeId = null; deadline = 0; pending.clear(); after = 0
            old
        }
        if (id != null) session.stopBrowserPreview(id)
    }

    private fun emit(id: String, kind: String, data: String, mid: String?, line: Int?) {
        var overflow = false
        synchronized(lock) {
            if (!_enabled.value || activeId != id || pending.any { it.optString("kind") == "end" }) return
            if (pending.size >= 66) overflow = true
            else pending.add(JSONObject().put("id", UUID.randomUUID().toString()).put("kind", kind).put("data", data)
                .put("mid", mid ?: JSONObject.NULL).put("mLineIndex", line ?: JSONObject.NULL))
        }
        if (overflow) disable("Webvorschau beendet · Nachrichtenlimit erreicht")
    }

    private fun run() {
        var lastTicket: StudioClient.CameraDeviceTicket? = null
        try {
            while (_enabled.value && !closed) {
                check(session.browserPreviewAvailable()) { "Kamera ist nicht mehr verfügbar" }
                val proof = ticket() ?: throw IllegalStateException("Kameraticket nicht verfügbar")
                lastTicket = proof
                val batch: List<JSONObject>; val sentId: String?; val sentAfter: Long
                synchronized(lock) { batch = pending.take(16); sentId = activeId; sentAfter = after }
                val request = JSONObject().put("deviceId", proof.deviceId).put("sessionId", sentId ?: JSONObject.NULL)
                    .put("available", true).put("after", sentAfter).put("events", JSONArray(batch))
                val started = SystemClock.elapsedRealtime()
                val response = client.pollCameraPreview(proof, request)
                if (!_enabled.value || closed) break
                check(session.browserPreviewAvailable()) { "Kamera ist nicht mehr verfügbar" }
                synchronized(lock) {
                    check(activeId == sentId && after == sentAfter)
                    batch.forEach { item -> pending.removeAll { it.getString("id") == item.getString("id") } }
                }
                val incoming = if (response.isNull("session")) null else response.getJSONObject("session")
                if (incoming == null) {
                    if (sentId != null) { session.stopBrowserPreview(sentId); synchronized(lock) { activeId = null; after = 0; pending.clear(); deadline = 0 } }
                } else {
                    val id = incoming.getString("sessionId")
                    check(UUID.fromString(id).toString() == id && incoming.getString("deviceId") == proof.deviceId)
                    check(sentId == null || sentId == id)
                    val remaining = incoming.getLong("leaseRemainingMs")
                    val expires = previewLeaseDeadline(started, SystemClock.elapsedRealtime(), remaining) ?: error("Vorschau abgelaufen")
                    synchronized(lock) { if (!_enabled.value) return; deadline = expires; activeId = id }
                    val events = incoming.getJSONArray("events"); check(events.length() <= 66)
                    if (sentId == null) check(events.length() > 0 && events.getJSONObject(0).getString("kind") == "offer")
                    for (index in 0 until events.length()) {
                        val event = events.getJSONObject(index); val seq = event.getLong("seq")
                        synchronized(lock) { check(seq == after + 1); after = seq }
                        when (event.getString("kind")) {
                            "offer" -> {
                                check(seq == 1L && sentId == null && event.getString("id") == id)
                                val offer = event.getString("data"); check(offer.length <= 64 * 1024)
                                session.startBrowserPreview(id, offer, { _enabled.value && activeId == id && SystemClock.elapsedRealtime() < deadline }) { kind, data, mid, line -> emit(id, kind, data, mid, line) }
                                if (_enabled.value && activeId == id) _status.value = "Webvorschau verbindet …"
                            }
                            "ice" -> {
                                val data = event.getString("data"); check(data.length <= 1024)
                                val mid = if (event.isNull("mid")) null else event.getString("mid")
                                val line = if (event.isNull("mLineIndex")) null else event.getInt("mLineIndex")
                                session.browserPreviewCandidate(id, data, mid, line)
                            }
                            "ice_end" -> Unit
                            "end" -> { disable("Webvorschau im Dashboard beendet"); break }
                            else -> error("Ungültige Vorschau-Nachricht")
                        }
                    }
                }
                Thread.sleep(1000)
            }
        } catch (_: Exception) {
            disable("Webvorschau nicht erreichbar · Aufnahme läuft unabhängig weiter")
        } finally {
            if (_enabled.value) disable()
            // Revocation uses the same serial worker, after any in-flight poll. The local
            // peer is already closed and a new opt-in cannot start until this worker exits.
            lastTicket?.let { proof -> runCatching {
                client.pollCameraPreview(proof, JSONObject().put("deviceId", proof.deviceId)
                    .put("sessionId", JSONObject.NULL).put("available", false).put("after", 0).put("events", JSONArray()))
            } }
            synchronized(lock) { worker = null }
        }
    }

    override fun close() { closed = true; disable(); watchdog.shutdownNow() }
}
