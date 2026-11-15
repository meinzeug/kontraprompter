package de.kontrakurs.kontraprompter.camera

import android.content.Context
import android.content.ContextWrapper
import android.test.InstrumentationTestCase
import de.kontrakurs.kontraprompter.cloud.StudioClient
import de.kontrakurs.kontraprompter.video.LiveSession
import de.kontrakurs.kontraprompter.video.LocalRecording
import de.kontrakurs.kontraprompter.video.RtcMp4Recorder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URL
import java.util.UUID
import javax.net.ssl.HttpsURLConnection

/** Fixture-only: synthetic events, real Android journal/transport and disposable loopback server. */
class CameraLocalE2eTest : InstrumentationTestCase() {
    private val base = "https://127.0.0.1:3395"
    private val password = "camera-e2e-fixture-only"
    private class FixtureContext(base: Context, private val dir: File) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = dir
    }
    private fun id() = UUID.randomUUID().toString()
    private fun request(path: String, method: String, cookie: String?, data: JSONObject? = null): Pair<JSONObject, String?> {
        val c = URL(base + path).openConnection() as HttpsURLConnection
        try {
            c.requestMethod = method; c.connectTimeout = 5000; c.readTimeout = 15000; c.instanceFollowRedirects = false
            c.setRequestProperty("Origin", base); c.setRequestProperty("Content-Type", "application/json")
            if (cookie != null) c.setRequestProperty("Cookie", cookie)
            if (data != null) { c.doOutput = true; c.outputStream.use { it.write(data.toString().toByteArray()) } }
            check(c.responseCode in 200..299) { "Fixture HTTP ${c.responseCode} for $path" }
            val result = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            return result to c.getHeaderField("Set-Cookie")?.substringBefore(';')
        } finally { c.disconnect() }
    }
    fun testSyntheticRecorderEventsReachRealServerThroughJournalAndStudioClient() = runBlocking {
        val target = instrumentation.targetContext
        check(target.packageName == "de.kontrakurs.kontraprompter.journalv4real")
        val files = File(target.cacheDir, "camera-e2e-${id()}"); check(files.mkdir())
        val context = FixtureContext(target, files)
        var session: LiveSession? = null; var controller: RemoteCameraController? = null
        try {
            val browser = checkNotNull(request("/api/login", "POST", null, JSONObject().put("password", password)).second)
            val studio = StudioClient(); studio.login(password)
            val identity = CameraDeviceIdentity.loadOrCreate(context)
            studio.registerCameraDevice(identity); val ticket = studio.startCameraDeviceSession(identity)
            val journal = RemoteCameraJournal.open(context, identity.id)
            val live = LiveSession(context, host = true, fixtureNoNetwork = true); session = live
            val camera = RemoteCameraController.open(journal, live); controller = camera
            camera.poll { studio.pollCameraDevice(ticket, it) }
            val project = request("/api/projects", "POST", browser, JSONObject().put("title", "Synthetic device E2E")
                .put("script", "Fixture only").put("sources", org.json.JSONArray()).put("titles", org.json.JSONArray())
                .put("thumbnail", "").put("broll", org.json.JSONArray())).first.getString("id")
            val recording = id(); val commandId = id()
            request("/api/camera-devices/${identity.id}/commands", "POST", browser, JSONObject()
                .put("id", commandId).put("type", "record_start").put("recordingId", recording).put("projectId", project))
            val sequence = journal.reserveSequence()
            val delivery = studio.pollCameraDevice(ticket, CameraPollRequest(sequence, CameraReport("idle", null, null, cameraFront = false), emptyList()))
            val command = checkNotNull(delivery.command); assertEquals(commandId, command.id)
            // The action itself is deliberately synthetic; no video track or codec is created.
            assertEquals("admitting", journal.decide(command, expired = false, canStart = true).result)
            journal.finishAdmission(command, admitted = true)
            @Suppress("UNCHECKED_CAST")
            val recordingState = LiveSession::class.java.getDeclaredField("_recording").apply { isAccessible = true }.get(live) as MutableStateFlow<LocalRecording>
            recordingState.value = LocalRecording.Preparing(recording)
            val emit = LiveSession::class.java.getDeclaredMethod("recordingEvent", RtcMp4Recorder.Event::class.java).apply { isAccessible = true }
            emit.invoke(live, RtcMp4Recorder.Event.Recording(id(), 1280, 720))
            assertEquals(LocalRecording.Preparing(recording), recordingState.value)
            emit.invoke(live, RtcMp4Recorder.Event.Recording(recording, 1280, 720))
            // The server commits the ACK, then the transport loses its response.
            // The durable client decision must remain pending and be safely retried.
            var lostResponse = false
            try {
                camera.poll { request ->
                    check(request.acknowledgements.any { it.id == commandId && it.result == "enqueued" })
                    studio.pollCameraDevice(ticket, request)
                    throw IOException("synthetic lost poll response")
                }
            } catch (expected: IOException) { lostResponse = true }
            assertTrue(lostResponse)
            val beforeRetry = journal.snapshot().decisions.single { it.command.id == commandId }
            assertEquals("enqueued", beforeRetry.result); assertTrue(beforeRetry.pendingAck)
            camera.poll { request ->
                check(request.acknowledgements.any { it.id == commandId && it.result == "enqueued" })
                studio.pollCameraDevice(ticket, request)
            }
            val afterRetry = journal.snapshot().decisions.single { it.command.id == commandId }
            assertEquals("enqueued", afterRetry.result); assertFalse(afterRetry.pendingAck)
            val devices = request("/api/camera-devices", "GET", browser).first.getJSONArray("devices")
            val reported = (0 until devices.length()).map { devices.getJSONObject(it) }.single { it.getString("id") == identity.id }
            assertEquals("recording", reported.getString("phase")); assertEquals(recording, reported.getString("recordingId"))
            assertEquals(project, reported.getString("projectId")); assertEquals("enqueued", reported.getJSONObject("lastCommand").getString("result"))
            assertEquals("recording", journal.snapshot().captures.single().phase)
            emit.invoke(live, RtcMp4Recorder.Event.Failed(recording, "synthetic failure", null))
            camera.poll { studio.pollCameraDevice(ticket, it) }
            val failed = request("/api/camera-devices", "GET", browser).first.getJSONArray("devices").getJSONObject(0)
            assertEquals("error", failed.getString("phase")); assertEquals(recording, failed.getString("recordingId"))
            assertEquals("error", journal.snapshot().captures.single().phase)
        } finally {
            // No recorder exists in this fixture; avoid waiting on a synthetic active state
            // when a preceding assertion or transport fails.
            session?.let { live ->
                @Suppress("UNCHECKED_CAST")
                val state = LiveSession::class.java.getDeclaredField("_recording").apply { isAccessible = true }.get(live) as MutableStateFlow<LocalRecording>
                val currentId = when (val current = state.value) {
                    is LocalRecording.Preparing -> current.id
                    is LocalRecording.Recording -> current.id
                    is LocalRecording.Paused -> current.id
                    is LocalRecording.Finalizing -> current.id
                    else -> null
                }
                if (currentId != null) state.value = LocalRecording.Failed("synthetic fixture cleanup", false, currentId)
            }
            controller?.let { withTimeout(5000) { it.close() } }; session?.close()
            check(files.deleteRecursively())
        }
    }
}
