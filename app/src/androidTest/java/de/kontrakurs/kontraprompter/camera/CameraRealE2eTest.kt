package de.kontrakurs.kontraprompter.camera

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.media.MediaExtractor
import android.media.MediaFormat
import android.test.InstrumentationTestCase
import de.kontrakurs.kontraprompter.cloud.StudioClient
import de.kontrakurs.kontraprompter.network.LanState
import de.kontrakurs.kontraprompter.video.LiveSession
import de.kontrakurs.kontraprompter.video.LocalRecording
import de.kontrakurs.kontraprompter.video.RtcMp4Recorder
import de.kontrakurs.kontraprompter.video.RtcVideo
import de.kontrakurs.kontraprompter.video.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URL
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection

/** Real camera/microphone, test-only LAN readiness; isolated loopback server and app UID. */
class CameraRealE2eTest : InstrumentationTestCase() {
    // Must remain false until the device-test plan and cleanup are independently reviewed.
    private val realDeviceFixtureEnabled = false
    private val base = "https://127.0.0.1:3395"
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
            return JSONObject(c.inputStream.bufferedReader().use { it.readText() }) to c.getHeaderField("Set-Cookie")?.substringBefore(';')
        } finally { c.disconnect() }
    }
    private fun verifyTracks(file: File) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val wanted = listOf("video/", "audio/")
            for (prefix in wanted) {
                val index = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith(prefix) == true }
                    ?: error("Missing $prefix track")
                extractor.selectTrack(index)
                check(extractor.readSampleData(ByteBuffer.allocate(4 * 1024 * 1024), 0) > 0) { "Empty $prefix track" }
                extractor.unselectTrack(index); extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            }
        } finally { extractor.release() }
    }
    fun testActualRecorderWithLostAckAndLocalWatchdog() = runBlocking {
        check(realDeviceFixtureEnabled) { "Real recorder fixture disabled pending independent review" }
        val target = instrumentation.targetContext
        check(target.packageName == "de.kontrakurs.kontraprompter.journalv4real")
        check(target.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
        check(target.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
        val dir = File(target.filesDir, "camera-real-${id()}"); check(dir.mkdir())
        val context = FixtureContext(target, dir)
        val marker = File(target.filesDir, "camera-real-released.json"); check(!marker.exists())
        var live: LiveSession? = null; var controller: RemoteCameraController? = null
        var recording: String? = null; var finishedCleanly = false
        val watchdog = Executors.newSingleThreadScheduledExecutor { work -> Thread(work, "FixtureEmergencyStop").apply { isDaemon = true } }
        try {
            val browser = checkNotNull(request("/api/login", "POST", null, JSONObject().put("password", "camera-e2e-fixture-only")).second)
            val studio = StudioClient(); studio.login("camera-e2e-fixture-only")
            val identity = CameraDeviceIdentity.loadOrCreate(context)
            studio.registerCameraDevice(identity); val ticket = studio.startCameraDeviceSession(identity)
            val journal = RemoteCameraJournal.open(context, identity.id)
            val session = LiveSession(context, host = true, fixtureNoNetwork = true); live = session
            // No network pairing is claimed: only the test fixture establishes
            // local readiness, after which the real camera track is created.
            @Suppress("UNCHECKED_CAST")
            val lanState = session.lan.javaClass.getDeclaredField("_state").apply { isAccessible = true }.get(session.lan) as MutableStateFlow<LanState>
            lanState.value = LanState(role = "host", status = "Gekoppelt; Video wird vorbereitet", ready = true)
            checkNotNull(session.lan.onReady).invoke()
            withTimeout(25_000) { session.lan.state.first { it.status == "Kamerabild verfügbar; Verbindung wird aufgebaut" } }
            withTimeout(10_000) { while (!session.canStartRemoteRecording()) delay(100) }
            val camera = RemoteCameraController.open(journal, session); controller = camera
            camera.poll { studio.pollCameraDevice(ticket, it) }
            val project = request("/api/projects", "POST", browser, JSONObject().put("title", "Real camera fixture")
                .put("script", "Fixture only").put("sources", org.json.JSONArray()).put("titles", org.json.JSONArray())
                .put("thumbnail", "").put("broll", org.json.JSONArray())).first.getString("id")
            val captureId = id(); recording = captureId; val commandId = id()
            // A pending START may be delivered after the deadline. Keep trying
            // the id-bound local stop until the fixture has demonstrably closed.
            val emergency = FixtureEmergencyStop(captureId) { session.stopRecording(it) }
            watchdog.scheduleAtFixedRate({ emergency.tick() }, 50, 1, TimeUnit.SECONDS)
            request("/api/camera-devices/${identity.id}/commands", "POST", browser, JSONObject()
                .put("id", commandId).put("type", "record_start").put("recordingId", captureId).put("projectId", project))
            camera.poll { studio.pollCameraDevice(ticket, it) } // actual controller dispatches START
            withTimeout(25_000) { session.recording.first { it is LocalRecording.Recording && it.id == captureId } }
            var lost = false
            try {
                camera.poll { poll ->
                    check(poll.acknowledgements.any { it.id == commandId && it.result == "enqueued" })
                    studio.pollCameraDevice(ticket, poll) // server accepted it
                    throw IOException("synthetic lost ACK response")
                }
            } catch (_: IOException) { lost = true }
            check(lost); check(journal.snapshot().decisions.single { it.command.id == commandId }.pendingAck)
            camera.poll { studio.pollCameraDevice(ticket, it) }
            val afterRetry = journal.snapshot().decisions.filter { it.command.id == commandId }
            check(afterRetry.size == 1 && !afterRetry.single().pendingAck && afterRetry.single().result == "enqueued")
            delay(3_000)
            request("/api/camera-devices/${identity.id}/commands", "POST", browser, JSONObject()
                .put("id", id()).put("type", "record_stop").put("recordingId", captureId))
            camera.poll { studio.pollCameraDevice(ticket, it) }
            val terminal = withTimeout(35_000) { session.recording.first { !it.isActive() && it !is LocalRecording.Idle } }
            check(terminal is LocalRecording.Ready && terminal.id == captureId) { "Recorder did not produce a verified ready file" }
            verifyTracks(terminal.file)
            camera.poll { studio.pollCameraDevice(ticket, it) }
            val device = request("/api/camera-devices", "GET", browser).first.getJSONArray("devices").getJSONObject(0)
            check(device.getString("phase") == "ready" && device.getString("recordingId") == captureId)
            check(terminal.file.canonicalPath.startsWith(dir.canonicalPath + File.separator))
            finishedCleanly = true
        } finally {
            val session = live
            recording?.let { if (session != null) runCatching { session.stopRecording(it) } }
            val recorderStopped = session == null || runCatching {
                withTimeout(35_000) { session.recording.first { !it.isActive() } }
                true
            }.getOrDefault(false)
            var controllerStopped = controller == null
            if (recorderStopped && controller != null) {
                val done = CountDownLatch(1); val c = controller!!; val failure = AtomicReference<Throwable?>(null)
                Thread({ try { runBlocking { c.close() } } catch (error: Throwable) { failure.set(error) } finally { done.countDown() } }, "FixtureControllerClose").apply { isDaemon = true; start() }
                controllerStopped = done.await(20, TimeUnit.SECONDS) && failure.get() == null
            }
            session?.close()
            val released = recorderStopped && controllerStopped && runCatching {
                withTimeout(25_000) { while (!RtcMp4Recorder.fixtureRecorderAvailable() || !RtcVideo.cameraAvailable()) delay(100) }
                true
            }.getOrDefault(false)
            if (released) {
                watchdog.shutdownNow()
                marker.outputStream().use { it.write(JSONObject().put("released", true).put("testSucceeded", finishedCleanly).toString().toByteArray()); it.fd.sync() }
            }
            // No deletion here. The host may remove the isolated package only
            // after a successful test, this release marker, and OS-level checks.
        }
    }
}
