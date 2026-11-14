package de.kontrakurs.kontraprompter

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.content.Intent
import android.content.Context
import android.hardware.input.InputManager
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.view.KeyEvent
import android.view.InputDevice
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import de.kontrakurs.kontraprompter.cloud.CloudUploadViewModel
import de.kontrakurs.kontraprompter.network.LanState
import de.kontrakurs.kontraprompter.cloud.StudioClient
import de.kontrakurs.kontraprompter.video.LiveSession
import de.kontrakurs.kontraprompter.video.PairedCaptureState
import de.kontrakurs.kontraprompter.video.RtcVideo
import de.kontrakurs.kontraprompter.video.SavedRecording
import de.kontrakurs.kontraprompter.video.savedRecordings
import de.kontrakurs.kontraprompter.video.UnverifiedRecording
import de.kontrakurs.kontraprompter.video.unverifiedRecordings
import de.kontrakurs.kontraprompter.video.isActive
import de.kontrakurs.kontraprompter.video.recordingStopDescription
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val Red = Accent
private data class RemoteEvent(val type: String, val completed: CompletableDeferred<Unit>, val stillValid: (() -> Boolean)? = null)

class MainActivity : ComponentActivity() {
    internal var shutterHandler: ((InputDevice, Int, Long) -> Unit)? = null
    private val shutterKeys = ShutterKeyTracker()
    internal var selectedVolumeRemote: String? = null
    internal fun clearShutterKeys() = shutterKeys.clear()

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val handler = shutterHandler
        val device = event.device
        if (handler != null && device != null && Build.VERSION.SDK_INT >= 29 &&
            isSelfieTrigger(device.isExternal, device.isVirtual, device.vendorId, device.productId,
                device.name, device.descriptor, selectedVolumeRemote, event.keyCode)) {
            when (shutterKeys.event(true, device.descriptor, event.keyCode, event.action, event.repeatCount, event.eventTime, event.isCanceled)) {
                ShutterKeyDecision.SHORT_PRESS -> { handler(device, event.keyCode, event.eventTime); return true }
                ShutterKeyDecision.CONSUME -> return true
                ShutterKeyDecision.PASS -> Unit
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            KontraTheme {
                KontraApp(this)
            }
        }
    }
}

@Composable
private fun KontraApp(activity: MainActivity) {
    val repository = remember { ScriptRepository(activity) }
    val preferences = remember { activity.getSharedPreferences("prompter_settings", Activity.MODE_PRIVATE) }
    var cameraPreferFront by rememberSaveable { mutableStateOf(preferences.getBoolean("camera_prefer_front", true)) }
    var scripts by remember { mutableStateOf(repository.load().ifEmpty { listOf(Script()) }) }
    var selectedId by rememberSaveable { mutableStateOf(repository.currentId ?: scripts.first().id) }
    val selected = scripts.find { it.id == selectedId } ?: scripts.first()
    var page by rememberSaveable { mutableStateOf("roles") }
    var helpReturn by rememberSaveable { mutableStateOf("roles") }
    var session by remember { mutableStateOf<LiveSession?>(null) }
    LaunchedEffect(page) { if (page == "roles") cameraPreferFront = preferences.getBoolean("camera_prefer_front", true) }
    val cloud: CloudUploadViewModel = viewModel()
    val studioClient = cloud.client
    var remoteEnabled by remember { mutableStateOf(false) }
    var remoteStatus by remember { mutableStateOf("Aus") }
    var playbackStatus by remember { mutableStateOf("Pausiert") }
    val remoteActions = remember { Channel<RemoteEvent>() }
    val emptyVideo = remember { kotlinx.coroutines.flow.MutableStateFlow<RtcVideo?>(null) }
    val video by (session?.video ?: emptyVideo).collectAsState()
    val emptyLan = remember { kotlinx.coroutines.flow.MutableStateFlow(LanState()) }
    val lanState by (session?.lan?.state ?: emptyLan).collectAsState()
    val emptyRecording = remember { kotlinx.coroutines.flow.MutableStateFlow<de.kontrakurs.kontraprompter.video.LocalRecording>(de.kontrakurs.kontraprompter.video.LocalRecording.Idle) }
    val recording by (session?.recording ?: emptyRecording).collectAsState()
    val emptyCapture = remember { kotlinx.coroutines.flow.MutableStateFlow(PairedCaptureState()) }
    val pairedCapture by (session?.pairedCapture?.state ?: emptyCapture).collectAsState()
    var selectedVolumeRemote by remember { mutableStateOf(preferences.getString("volume_remote_descriptor", null)) }
    var inputRefresh by remember { mutableIntStateOf(0) }
    DisposableEffect(activity, page, session, lanState.ready, selectedVolumeRemote, selected.studioProjectId) {
        val activeSession = session?.takeIf { !it.host && page == "prompter" && lanState.ready }
        val clicks = ShutterClickMachine()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val inputs = activity.getSystemService(Context.INPUT_SERVICE) as InputManager
        val inputListener = object : InputManager.InputDeviceListener {
            override fun onInputDeviceAdded(deviceId: Int) = Unit
            override fun onInputDeviceRemoved(deviceId: Int) { clicks.clear(); activity.clearShutterKeys() }
            override fun onInputDeviceChanged(deviceId: Int) { clicks.clear(); activity.clearShutterKeys() }
        }
        val listening = activeSession != null && runCatching { inputs.registerInputDeviceListener(inputListener, Handler(Looper.getMainLooper())); true }.getOrDefault(false)
        activity.selectedVolumeRemote = selectedVolumeRemote
        activity.shutterHandler = activeSession?.takeIf { listening }?.let { current ->
            fun dispatch(gesture: ShutterGesture, context: ShutterContext) {
                val now = current.pairedCapture.state.value
                val input = InputDevice.getDevice(context.deviceId)
                val deviceCurrent = input != null && input.isExternal && !input.isVirtual && input.descriptor == context.descriptor
                if (!shutterContextCurrent(context, now.phase, now.recordingId, now.pending, current.lan.isApprovedConnection(context.link.state), deviceCurrent)) {
                    current.pairedCapture.triggerUnavailable(); return
                }
                when (shutterAction(context, gesture)) {
                    ShutterAction.START -> if (!current.pairedCapture.toggle(context.projectId)) current.pairedCapture.triggerUnavailable()
                    ShutterAction.CAPTURE_TOGGLE -> if (!current.pairedCapture.toggle()) current.pairedCapture.triggerUnavailable()
                    ShutterAction.SCROLL -> scope.launch {
                        val event = RemoteEvent("toggle", CompletableDeferred()) {
                            val state = current.pairedCapture.state.value
                            val device = InputDevice.getDevice(context.deviceId)
                            shutterContextCurrent(context, state.phase, state.recordingId, state.pending, current.lan.isApprovedConnection(context.link.state),
                                device != null && device.isExternal && !device.isVirtual && device.descriptor == context.descriptor)
                        }
                        try {
                            if (withTimeoutOrNull(2_000) { remoteActions.send(event); event.completed.await(); true } != true)
                                current.pairedCapture.triggerUnavailable()
                        } catch (error: CancellationException) { throw error }
                        catch (_: Exception) { current.pairedCapture.triggerUnavailable() }
                    }
                    ShutterAction.NONE -> {
                        if (gesture == ShutterGesture.DOUBLE && context.phase in setOf("idle", "ready", "partial", "failed"))
                            android.widget.Toast.makeText(activity, "Vor der Aufnahme einmal kurz drücken.", android.widget.Toast.LENGTH_SHORT).show()
                        else current.pairedCapture.triggerUnavailable()
                    }
                }
            }
            { device, code, time ->
                val state = current.pairedCapture.state.value
                val marker = current.lan.approvedConnectionMarker()
                if (state.pending || marker == null || (state.phase in setOf("recording", "paused") && state.recordingId == null)) {
                    clicks.clear(); current.pairedCapture.triggerUnavailable()
                } else {
                    val context = ShutterContext(state.phase, state.recordingId, selected.studioProjectId, ShutterLink(marker), device.id, device.descriptor)
                    when (val result = clicks.press(device.descriptor, code, time, context)) {
                        is ShutterPress.Wait -> {
                            result.overdue?.let { dispatch(ShutterGesture.SINGLE, it) }
                            scope.launch {
                                delay(400)
                                clicks.expire(device.descriptor, result.token, android.os.SystemClock.uptimeMillis())?.let { dispatch(ShutterGesture.SINGLE, it) }
                            }
                        }
                        is ShutterPress.Double -> dispatch(ShutterGesture.DOUBLE, result.context)
                        ShutterPress.Changed -> current.pairedCapture.triggerUnavailable()
                        ShutterPress.Ignored -> Unit
                    }
                }
            }
        }
        onDispose { activity.shutterHandler = null; activity.selectedVolumeRemote = null; if (listening) inputs.unregisterInputDeviceListener(inputListener); activity.clearShutterKeys(); clicks.clear(); scope.cancel() }
    }
    var recordings by remember { mutableStateOf<List<SavedRecording>>(emptyList()) }
    var unverified by remember { mutableStateOf<List<UnverifiedRecording>>(emptyList()) }
    var uploadRecordingId by rememberSaveable { mutableStateOf<String?>(null) }
    var recordingsLoaded by remember { mutableStateOf(false) }
    var pendingExportId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingUnverifiedExport by rememberSaveable { mutableStateOf(false) }
    val exportScope = rememberCoroutineScope()
    val exportDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("video/mp4")) { uri ->
        val id = pendingExportId
        val raw = pendingUnverifiedExport
        pendingExportId = null
        pendingUnverifiedExport = false
        if (uri != null && id != null) exportScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    if (raw) {
                        check(RtcVideo.cameraAvailable()) { "Kamera wird noch verwendet" }
                        val item = unverifiedRecordings(activity).firstOrNull { it.id == id } ?: error("Datei nicht verfügbar")
                        activity.contentResolver.openOutputStream(uri, "w")?.use { output -> item.file.inputStream().buffered().use { it.copyTo(output) } }
                            ?: error("Zieldatei nicht verfügbar")
                        check(item.file.length() == item.bytes) { "Datei hat sich während des Exports geändert" }
                        return@runCatching
                    }
                    val item = savedRecordings(activity).firstOrNull { it.id == id } ?: error("Aufnahme nicht gefunden")
                    val digest = java.security.MessageDigest.getInstance("SHA-256")
                    item.file.inputStream().buffered().use { input ->
                        val buffer = ByteArray(256 * 1024)
                        while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
                    }
                    val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
                    check(hash == item.sha256) { "Aufnahmedatei wurde verändert" }
                    activity.contentResolver.openOutputStream(uri, "w")?.use { output -> item.file.inputStream().buffered().use { it.copyTo(output) } }
                        ?: error("Zieldatei nicht verfügbar")
                }
            }
            android.widget.Toast.makeText(activity, if (result.isSuccess) "Video exportiert" else "Export fehlgeschlagen; die lokale Aufnahme bleibt erhalten.", android.widget.Toast.LENGTH_LONG).show()
        }
    }
    LaunchedEffect(page) {
        if (page == "recordings" || page == "upload") {
            recordingsLoaded = false
            recordings = withContext(Dispatchers.IO) { savedRecordings(activity) }
            unverified = withContext(Dispatchers.IO) { unverifiedRecordings(activity) }
            recordingsLoaded = true
        }
    }
    var cameraPermissionError by remember { mutableStateOf(false) }
    var cameraBusyError by remember { mutableStateOf(false) }
    BackHandler(enabled = page != "roles") {
        when (page) {
            "connection" -> if (recording.isActive() && recording !is de.kontrakurs.kontraprompter.video.LocalRecording.Failed) session?.stopRecording() else { session?.close(); session = null; page = "roles" }
            "help" -> page = helpReturn
            "scripts" -> page = "roles"
            "studio", "editor" -> page = "scripts"
            "recordings" -> page = "roles"
            "upload" -> page = "recordings"
            "settings", "calibration", "prompter" -> page = "editor"
            else -> page = "roles"
        }
    }
    fun startHost() {
        if (!RtcVideo.cameraAvailable()) { cameraBusyError = true; return }
        cameraBusyError = false; session?.close(); session = LiveSession(activity, true, cameraPreferFront); page = "connection"
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) { cameraPermissionError = false; startHost() } else cameraPermissionError = true
    }
    DisposableEffect(activity) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && session != null) {
                session?.close(); session = null
                if (page == "connection") page = "roles"
            }
            if (event == Lifecycle.Event.ON_STOP) { remoteEnabled = false; remoteStatus = "Aus" }
        }
        activity.lifecycle.addObserver(observer)
        onDispose { activity.lifecycle.removeObserver(observer); session?.close(); session = null }
    }
    var recordingScrollSync by rememberSaveable { mutableStateOf(preferences.getBoolean("recording_scroll_sync", false)) }
    var keepAwake by rememberSaveable { mutableStateOf(preferences.getBoolean("awake", true)) }
    var fontSize by rememberSaveable { mutableFloatStateOf(preferences.getFloat("font", 44f)) }
    var speed by rememberSaveable { mutableFloatStateOf(preferences.getFloat("speed", 35f)) }
    var reportedSpeed by remember { mutableFloatStateOf(speed) }
    var reportedFontSize by remember { mutableFloatStateOf(fontSize) }
    LaunchedEffect(speed, fontSize) {
        // Do not advertise a just-assigned value before Compose has rendered it.
        androidx.compose.runtime.withFrameNanos { }
        androidx.compose.runtime.withFrameNanos { }
        reportedSpeed = speed; reportedFontSize = fontSize
    }
    var width by rememberSaveable { mutableFloatStateOf(preferences.getFloat("width", .9f)) }
    var lineSpacing by rememberSaveable { mutableFloatStateOf(preferences.getFloat("line", 1.25f)) }
    var textH by rememberSaveable { mutableStateOf(preferences.getBoolean("textH", false)) }
    var textV by rememberSaveable { mutableStateOf(preferences.getBoolean("textV", false)) }
    var videoH by rememberSaveable { mutableStateOf(preferences.getBoolean("videoH", false)) }
    var videoV by rememberSaveable { mutableStateOf(preferences.getBoolean("videoV", false)) }
    var videoRotation by rememberSaveable { mutableIntStateOf(preferences.getInt("videoRotation", 0)) }
    var videoFill by rememberSaveable { mutableStateOf(preferences.getBoolean("videoFill", false)) }
    var videoOpacity by rememberSaveable { mutableFloatStateOf(preferences.getFloat("videoOpacity", 1f)) }
    var videoBrightness by rememberSaveable { mutableFloatStateOf(preferences.getFloat("videoBrightness", 1f)) }
    var textRotation by rememberSaveable { mutableIntStateOf(preferences.getInt("rotation", 0)) }
    var backgroundOpacity by rememberSaveable { mutableFloatStateOf(preferences.getFloat("opacity", .35f)) }
    var brightness by rememberSaveable { mutableFloatStateOf(preferences.getFloat("brightness", .8f)) }
    var focusLine by rememberSaveable { mutableStateOf(preferences.getBoolean("focus", true)) }
    var bold by rememberSaveable { mutableStateOf(preferences.getBoolean("bold", true)) }
    var countdown by rememberSaveable { mutableIntStateOf(preferences.getInt("countdown", 3)) }
    var savedPosition by rememberSaveable(selectedId) { mutableIntStateOf(preferences.getInt("position_$selectedId", 0)) }
    LaunchedEffect(selectedId, savedPosition) { delay(500); preferences.edit().putInt("position_$selectedId", savedPosition).apply() }
    LaunchedEffect(keepAwake, fontSize, speed, width, lineSpacing, textH, textV, textRotation, videoH, videoV, videoRotation, videoFill, videoOpacity, videoBrightness, backgroundOpacity, brightness, focusLine, bold, countdown) { preferences.edit().putBoolean("awake", keepAwake).putFloat("font", fontSize).putFloat("speed", speed).putFloat("width", width).putFloat("line", lineSpacing).putBoolean("textH", textH).putBoolean("textV", textV).putInt("rotation", textRotation).putBoolean("videoH", videoH).putBoolean("videoV", videoV).putInt("videoRotation", videoRotation).putBoolean("videoFill", videoFill).putFloat("videoOpacity", videoOpacity).putFloat("videoBrightness", videoBrightness).putFloat("opacity", backgroundOpacity).putFloat("brightness", brightness).putBoolean("focus", focusLine).putBoolean("bold", bold).putInt("countdown", countdown).apply() }
    fun update(script: Script) { scripts = scripts.map { if (it.id == script.id) preserveNoticeOnEdit(it, script) else it }; repository.save(scripts) }
    fun select(id: String) { selectedId = id; repository.currentId = id }
    LaunchedEffect(remoteEnabled) {
        if (remoteEnabled) {
            // A fresh identity prevents commands from an old session executing after reactivation.
            val deviceId = java.util.UUID.randomUUID().toString()
            val acknowledged = mutableListOf<String>()
            val applied = linkedSetOf<String>()
            while (isActive) {
                try {
                    val started = android.os.SystemClock.elapsedRealtime()
                    val commands = studioClient.pollDevice(deviceId, android.os.Build.MODEL, if (page == "prompter") playbackStatus else "Bereit", acknowledged, reportedSpeed, reportedFontSize)
                    acknowledged.clear()
                    remoteStatus = "Verbunden"
                    for (command in commands) {
                        val deadline = started + command.remainingMs.coerceIn(0, 10000)
                        if (android.os.SystemClock.elapsedRealtime() >= deadline) continue
                        if (command.id !in applied) {
                            when (command.type) {
                                "load" -> {
                                    val project = studioClient.projects().firstOrNull { it.id == command.projectId } ?: continue
                                    if (android.os.SystemClock.elapsedRealtime() >= deadline) continue
                                    val copy = Script(title = project.title, text = project.text, generationNotice = project.generationNotice, studioProjectId = project.id)
                                    scripts = scripts + copy; repository.save(scripts); select(copy.id)
                                    page = "prompter"
                                }
                                "set_speed", "set_font_size" -> {
                                    val value = remotePrompterValue(command.type, command.value) ?: continue
                                    val remaining = (deadline - android.os.SystemClock.elapsedRealtime()).coerceIn(0, 5000)
                                    val completed = withTimeoutOrNull(remaining) {
                                        if (command.type == "set_speed") speed = value else fontSize = value
                                        // Wait for composition and a subsequent frame before acknowledging.
                                        androidx.compose.runtime.withFrameNanos { }
                                        androidx.compose.runtime.withFrameNanos { }
                                        if (command.type == "set_speed") speed == value else fontSize == value
                                    } == true
                                    if (!completed) break
                                }
                                "play", "pause", "reset" -> {
                                    if (page != "prompter") page = "prompter"
                                    val event = RemoteEvent(command.type, CompletableDeferred())
                                    val remaining = (deadline - android.os.SystemClock.elapsedRealtime()).coerceIn(0, 5000)
                                    val completed = withTimeoutOrNull(remaining) { remoteActions.send(event); event.completed.await(); true } ?: false
                                    if (!completed) break
                                }
                                else -> continue
                            }
                            applied.add(command.id)
                            if (applied.size > 128) applied.remove(applied.first())
                        }
                        acknowledged.add(command.id)
                    }
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { remoteStatus = "Verbindung unterbrochen; erneuter Versuch" }
                delay(1500)
            }
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            val result = runCatching { activity.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } }
            result.getOrNull()?.let { update(selected.copy(text = it)) }
                ?: android.widget.Toast.makeText(activity, "Datei konnte nicht gelesen werden", android.widget.Toast.LENGTH_LONG).show()
        }
    }
    DisposableEffect(keepAwake, page, brightness, session) {
        // The camera runs only in the foreground; sleeping its screen would end the live session.
        if ((keepAwake && page == "prompter") || session?.host == true) activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val attrs = activity.window.attributes
        attrs.screenBrightness = if (page == "prompter") brightness else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        activity.window.attributes = attrs
        onDispose { activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); val a = activity.window.attributes; a.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE; activity.window.attributes = a }
    }
    CompositionLocalProvider(
        LocalRemoteIndicator provides if (remoteEnabled) RemoteIndicator(remoteStatus) { remoteEnabled = false; remoteStatus = "Aus" } else null,
        LocalCameraIndicator provides session?.let { CameraIndicator(lanState.status, lanState.status == "Videoverbindung hergestellt") { page = "connection" } }
    ) {
    AppBackground {
        when (page) {
            "roles" -> AppPage("Bereit für deinen Auftritt.", "KONTRAPROMPTER", "Verbinde Kamera und Text – und behalte alles im Blick.") {
                Spacer(Modifier.height(8.dp))
                Text("Dieses Gerät verwenden als", style = MaterialTheme.typography.titleMedium, color = SoftText)
                RoleTile("Teleprompter", "Skripte lesen und fernsteuern", camera = false) { page = "scripts" }
                Toggle("Frontkamera verwenden (aus: Rückkamera)", cameraPreferFront) { cameraPreferFront = it; preferences.edit().putBoolean("camera_prefer_front", it).apply() }
                Text("Die Auswahl gilt für die nächste Kamerasitzung. Fehlt die gewählte Kamera, wird die andere verwendet. Bei aktiver Aufnahme bleibt sie unverändert.", color = SoftText, style = MaterialTheme.typography.bodySmall)
                RoleTile("Kamera", "Livebild für den Teleprompter", camera = true) {
                    if (ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startHost()
                    else cameraPermission.launch(Manifest.permission.CAMERA)
                }
                Action("Aufnahmen") { page = "recordings" }
                Action("Hilfe & Aufbau") { helpReturn = "roles"; page = "help" }
                if (cameraPermissionError) Text("Für die Kamera-Vorschau ist die Kameraerlaubnis erforderlich.", color = Color(0xffff7777))
                if (cameraBusyError) Text("Die vorherige Aufnahme oder Kamera wird noch abgeschlossen. Bitte erneut versuchen.", color = Color(0xffffcd8a))
                Spacer(Modifier.height(4.dp))
                Text("Live-Vorschau zwischen zwei Geräten. Aufnahmen mit Ton bleiben zunächst auf dem Kameragerät.", color = SoftText, style = MaterialTheme.typography.bodySmall)
            }
            "help" -> HelpScreen(onBack = { page = helpReturn })
            "connection" -> session?.let { active ->
                ConnectionScreen(active, cloud, onClose = { active.close(); session = null; page = "roles" }, onScripts = { page = "scripts" })
            } ?: Page("Nicht verbunden", "VERBINDUNG", onBack = { page = "roles" }) { Action("Zur Rollenwahl") { page = "roles" } }
            "scripts" -> Page("Deine Skripte", "TELEPROMPTER", onBack = { page = "roles" }) {
                MainAction("Skripte aus Videomaker öffnen") { page = "studio" }
                Action(if (session == null) "Kamera verbinden" else "Verbindung anzeigen") {
                    if (session == null) session = LiveSession(activity, false)
                    page = "connection"
                }
                Action("Neues Skript") { val s = Script(); scripts = scripts + s; repository.save(scripts); select(s.id); page = "editor" }
                Spacer(Modifier.height(6.dp))
                Text("Auf diesem Gerät", style = MaterialTheme.typography.titleMedium, color = SoftText)
                scripts.forEach { script ->
                    ScriptTile(script.title.ifBlank { "Ohne Titel" }, "${script.text.length} Zeichen") { select(script.id); page = "editor" }
                }
            }
            "recordings" -> Page("Aufnahmen", "LOKAL GESPEICHERT", onBack = { page = "roles" }) {
                if (recordings.isEmpty() && unverified.isEmpty()) Text("Noch keine Aufnahmen auf diesem Gerät.", color = SoftText)
                recordings.forEach { item ->
                    AppPanel {
                        Text(java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(item.createdAtMs)), style = MaterialTheme.typography.titleMedium)
                        Text("${item.durationUs / 1_000_000} Sek. · ${item.bytes / (1024 * 1024)} MB", color = SoftText, style = MaterialTheme.typography.bodyMedium)
                        if (item.partial) Text("Unvollständiges Teilvideo nach einem Fehler", color = Color(0xffffcd8a))
                        else recordingStopDescription(item.reason)?.let { Text(it, color = Color(0xffffcd8a)) }
                        MainAction("Video exportieren") { pendingExportId = item.id; exportDocument.launch("KontraPrompter-${item.id.take(8)}.mp4") }
                        if (!item.partial) Action("In Videomaker hochladen") { uploadRecordingId = item.id; recordingsLoaded = false; page = "upload" }
                    }
                }
                unverified.forEach { item ->
                    AppPanel {
                        Text("Nicht geprüfte Aufnahmedatei", style = MaterialTheme.typography.titleMedium)
                        Text("Die Aufnahme wurde unterbrochen. Die Datei kann unvollständig oder nicht abspielbar sein. Das Original bleibt auf dem Gerät.", color = Color(0xffffcd8a))
                        Text("${item.bytes / (1024 * 1024)} MB · ${item.id.take(8)}", color = SoftText)
                        Action("Ungeprüfte Datei exportieren") { pendingExportId = item.id; pendingUnverifiedExport = true; exportDocument.launch("KontraPrompter-ungeprueft-${item.id.take(8)}.mp4") }
                    }
                }
            }
            "upload" -> {
                val item = recordings.firstOrNull { it.id == uploadRecordingId }
                if (item != null && recordingsLoaded) CloudUploadScreen(item, studioClient, cloud.coordinator, onBack = { page = "recordings" })
                else Page("Aufnahme laden", "CLOUD-UPLOAD", onBack = { page = "recordings" }) {
                    Text(if (recordingsLoaded) "Diese lokale Aufnahme ist nicht mehr verfügbar." else "Aufnahme wird geladen …", color = SoftText)
                }
            }
            "studio" -> StudioScreen(studioClient, remoteEnabled, remoteStatus, onRemote = { remoteEnabled = it; remoteStatus = if (it) "Verbinde …" else "Aus" }, onSelect = { project ->
                val copy = Script(title = project.title, text = project.text, generationNotice = project.generationNotice, studioProjectId = project.id)
                scripts = scripts + copy; repository.save(scripts); select(copy.id); page = "editor"
            }, onBack = { page = "scripts" })
            "editor" -> Page("Skript bearbeiten", "LOKAL GESPEICHERT", onBack = { page = "scripts" }) {
                selected.generationNotice?.let { GenerationNoticePanel(it) }
                if (selected.generationNotice != null && selected.studioProjectId == null) Text("Diese lokale Kopie ist keinem aktuellen Studio-Skript zugeordnet. Für die Aufnahme kann bei aktiver Anmeldung ein eigenes Aufnahmeprojekt angelegt werden.", color = SoftText, style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(value = selected.title, onValueChange = { update(selected.copy(title = it)) }, label = { Text("Titel") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = selected.text, onValueChange = { update(selected.copy(text = it)) }, label = { Text("Skripttext") }, modifier = Modifier.fillMaxWidth().height(260.dp))
                MainAction("Teleprompter öffnen") { page = "prompter" }
                Action("TXT oder Markdown importieren") { importer.launch(arrayOf("text/*", "application/octet-stream")) }
                Action("Einstellungen") { page = "settings" }
                Action("Spiegelung kalibrieren") { page = "calibration" }
                Action("Hilfe & Aufbau") { helpReturn = "editor"; page = "help" }
            }
            "settings" -> Page("Einstellungen", "TELEPROMPTER", onBack = { page = "editor" }) {
                Text("Text und Tempo", style = MaterialTheme.typography.titleLarge)
                SettingSlider("Schriftgröße", fontSize, 20f..100f, "${fontSize.toInt()} sp") { fontSize = it }
                SettingSlider("Scrolltempo", speed, 5f..160f, "${speed.toInt()} dp/s") { speed = it }
                SettingSlider("Textbreite", width, .35f..1f, "${(width * 100).toInt()} %") { width = it }
                SettingSlider("Zeilenabstand", lineSpacing, 1f..2f, "${(lineSpacing * 100).toInt()} %") { lineSpacing = it }
                SettingSlider("Texthintergrund", backgroundOpacity, 0f..1f, "${(backgroundOpacity * 100).toInt()} %") { backgroundOpacity = it }
                SettingSlider("Displayhelligkeit", brightness, .05f..1f, "${(brightness * 100).toInt()} %") { brightness = it }
                Text("Kamerabild", style = MaterialTheme.typography.titleLarge)
                Toggle("Video horizontal spiegeln", videoH) { videoH = it }
                Toggle("Video vertikal spiegeln", videoV) { videoV = it }
                Toggle("Video füllt Bild (Beschnitt)", videoFill) { videoFill = it }
                Action("Video drehen: $videoRotation°") { videoRotation = (videoRotation + 90) % 360 }
                SettingSlider("Video-Deckkraft", videoOpacity, 0f..1f, "${(videoOpacity * 100).toInt()} %") { videoOpacity = it }
                SettingSlider("Video-Helligkeit", videoBrightness, 0f..1f, "${(videoBrightness * 100).toInt()} %") { videoBrightness = it }
                Toggle("Aufnahme und Teleprompter synchron", recordingScrollSync) { recordingScrollSync = it; preferences.edit().putBoolean("recording_scroll_sync", it).apply() }
                Text("Bei bestätigter Aufnahme startet oder pausiert der Text automatisch. Bei Stopp bleibt er stehen.", color = SoftText, style = MaterialTheme.typography.bodyMedium)
                Text("Bluetooth-Auslöser", style = MaterialTheme.typography.titleLarge)
                Text("Vor der Aufnahme startet ein kurzer Druck die Kamera. Während der Aufnahme startet oder pausiert ein kurzer Druck nur den Teleprompter; ein schneller Doppeldruck pausiert oder setzt die Aufnahme fort. Der ITFIT Selfie P007 wird automatisch erkannt. Andere Auslöser mit Lautstärketasten bitte unten auswählen. Eingebaute Lautstärketasten bleiben unverändert.", color = SoftText, style = MaterialTheme.typography.bodyMedium)
                if (Build.VERSION.SDK_INT >= 29) {
                    val externalDevices = remember(inputRefresh) {
                        InputDevice.getDeviceIds().toList().mapNotNull { id -> InputDevice.getDevice(id) }.filter { it.isExternal && !it.isVirtual && it.descriptor.isNotBlank() }
                    }
                    if (externalDevices.isEmpty()) Text("Kein externes Eingabegerät verbunden.", color = SoftText)
                    externalDevices.forEach { device ->
                        QuietAction((if (selectedVolumeRemote == device.descriptor) "✓ " else "") + device.name.take(80)) {
                            selectedVolumeRemote = device.descriptor
                            preferences.edit().putString("volume_remote_descriptor", device.descriptor).apply()
                        }
                    }
                    if (selectedVolumeRemote != null) QuietAction("Geräteauswahl entfernen") {
                        selectedVolumeRemote = null; preferences.edit().remove("volume_remote_descriptor").apply()
                    }
                    QuietAction("Geräteliste aktualisieren") { inputRefresh++ }
                } else Text("Externe Auslöser werden ab Android 10 unterstützt.", color = SoftText)
                Text("Anzeige und Spiegelung", style = MaterialTheme.typography.titleLarge)
                Toggle("Text horizontal spiegeln", textH) { textH = it }
                Toggle("Text vertikal spiegeln", textV) { textV = it }
                Toggle("Fettschrift", bold) { bold = it }
                Toggle("Leselinie", focusLine) { focusLine = it }
                Toggle("Bildschirm eingeschaltet lassen", keepAwake) { keepAwake = it }
                Action("Text drehen: $textRotation°") { textRotation = (textRotation + 90) % 360 }
                Action("Countdown: $countdown Sekunden") { countdown = when (countdown) { 3 -> 5; 5 -> 10; else -> 3 } }
                Text("Fit zeigt das ganze Bild mit möglichen Balken. Fill füllt die Fläche und kann das Bild beschneiden.", color = Color.LightGray)
                Action("Zurück zum Editor") { page = "editor" }
            }
            "calibration" -> Page("Spiegelung kalibrieren", "AM SPIEGELGLAS", onBack = { page = "editor" }) {
                Text("Prüfe die Lesbarkeit im tatsächlichen Aufbau. Glas und Geräteausrichtung können unterschiedliche Einstellungen erfordern.")
                Box(Modifier.fillMaxWidth().height(220.dp).background(Color.Black), contentAlignment = Alignment.Center) {
                    Text("↑ OBEN\nL ←   → R\nUNTEN ↓", modifier = Modifier.graphicsLayer { scaleX = if (textH) -1f else 1f; scaleY = if (textV) -1f else 1f; rotationZ = textRotation.toFloat() }, color = Color.White, fontSize = 30.sp, textAlign = TextAlign.Center)
                }
                Toggle("Text horizontal spiegeln", textH) { textH = it }
                Toggle("Text vertikal spiegeln", textV) { textV = it }
                Action("Drehen: $textRotation°") { textRotation = (textRotation + 90) % 360 }
            }
            "prompter" -> key(selected.id) { Prompter(selected.text, selected.generationNotice, fontSize, speed, width, lineSpacing, textH, textV, textRotation, backgroundOpacity, focusLine, bold, countdown, savedPosition, { savedPosition = it }, { page = "editor" }, video, videoH, videoV, videoRotation, videoFill, videoOpacity * videoBrightness, lanState, pairedCapture, recordingScrollSync, selected.studioProjectId != null, { session?.pairedCapture?.toggle(selected.studioProjectId) }, { session?.pairedCapture?.request("stop") }, { session?.pairedCapture?.request("switch_camera") }, remoteActions) { playbackStatus = it } }
        }
    }
    }
}

@Composable
private fun Page(title: String, eyebrow: String, onBack: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) = AppPage(title, eyebrow, onBack = onBack, content = content)
@Composable private fun Action(label: String, onClick: () -> Unit) = QuietAction(label, onClick = onClick)
@Composable private fun Toggle(label: String, value: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Panel).padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge); Switch(checked = value, onCheckedChange = change)
    }
}
@Composable private fun SettingSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, display: String, change: (Float) -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Panel).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(label, style = MaterialTheme.typography.bodyLarge); Text(display, color = SoftText, style = MaterialTheme.typography.labelLarge) }
        Slider(value = value, onValueChange = change, valueRange = range)
    }
}

@Composable
private fun Prompter(text: String, notice: GenerationNotice?, fontSize: Float, speed: Float, width: Float, lineSpacing: Float, horizontal: Boolean, vertical: Boolean, rotation: Int, opacity: Float, focusLine: Boolean, bold: Boolean, countdownSeconds: Int, initialPosition: Int, savePosition: (Int) -> Unit, back: () -> Unit, video: RtcVideo?, videoH: Boolean, videoV: Boolean, videoRotation: Int, videoFill: Boolean, videoOpacity: Float, lanState: LanState, pairedCapture: PairedCaptureState, recordingScrollSync: Boolean, cloudProjectLinked: Boolean, onCaptureToggle: () -> Unit, onCaptureStop: () -> Unit, onCameraSwitch: () -> Unit, remoteActions: Channel<RemoteEvent>, onPlaybackStatus: (String) -> Unit) {
    val remote = LocalRemoteIndicator.current
    val scroll = rememberScrollState(initialPosition)
    val density = LocalDensity.current.density
    val scope = rememberCoroutineScope()
    var playing by remember { mutableStateOf(false) }
    var controls by remember { mutableStateOf(true) }
    var remaining by remember { mutableIntStateOf(0) }
    var countdownJob by remember { mutableStateOf<Job?>(null) }
    LaunchedEffect(playing, remaining) { onPlaybackStatus(if (remaining > 0) "Countdown" else if (playing) "Läuft" else "Pausiert") }
    // React only to camera-reported states, not to a button press or command acknowledgement.
    LaunchedEffect(recordingScrollSync, lanState.ready, pairedCapture.phase) {
        if (recordingScrollSync && lanState.ready) recordingScrollPlayback(pairedCapture.phase)?.let { shouldPlay ->
            countdownJob?.cancel(); remaining = 0; playing = shouldPlay
        }
    }
    LaunchedEffect(remoteActions) {
        for (event in remoteActions) {
            try {
                if (event.stillValid?.invoke() == false) { event.completed.completeExceptionally(IllegalStateException("Aufnahmezustand geändert")); continue }
                when (event.type) {
                    "play" -> { countdownJob?.cancel(); remaining = 0; playing = true }
                    "pause" -> { countdownJob?.cancel(); remaining = 0; playing = false }
                    "reset" -> { countdownJob?.cancel(); remaining = 0; playing = false; scroll.scrollTo(0) }
                    "toggle" -> { countdownJob?.cancel(); remaining = 0; playing = !playing }
                }
                event.completed.complete(Unit)
            } catch (e: Exception) { event.completed.completeExceptionally(e); throw e }
        }
    }
    LaunchedEffect(playing, speed, remaining) {
        if (playing && remaining == 0) {
            var last = 0L
            var fraction = 0f
            while (true) {
                withFrameNanos { now ->
                    if (last != 0L) fraction += ScrollMath.distance(speed, ((now - last) / 1_000_000_000f).coerceAtMost(.1f), density)
                    last = now
                }
                if (fraction >= 1f) {
                    val delta = fraction.toInt()
                    fraction -= delta
                    // A manual drag may cancel this child scroll; it must not cancel the frame loop.
                    launch { val moved = scroll.scrollBy(delta.toFloat()); if (moved < delta) playing = false }.join()
                }
            }
        }
    }
    LaunchedEffect(scroll.value) { savePosition(scroll.value) }
    val transform = LayerTransform(horizontal, vertical, rotation)
    Box(Modifier.fillMaxSize().background(Color.Black).clickable { controls = !controls }) {
        if (video != null) LivePreview(video, videoH, videoV, videoRotation, videoFill, videoOpacity, Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().graphicsLayer { scaleX = transform.scaleX; scaleY = transform.scaleY; rotationZ = transform.rotation.toFloat() }, contentAlignment = Alignment.TopCenter) {
            Column(Modifier.fillMaxSize().verticalScroll(scroll), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(180.dp))
                Text(text.ifBlank { "Füge im Editor deinen Text ein." }, modifier = Modifier.fillMaxWidth(width).background(Color.Black.copy(alpha = opacity)).padding(8.dp), color = Color.White, fontSize = fontSize.sp, lineHeight = (fontSize * lineSpacing).sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)
                Spacer(Modifier.height(400.dp))
            }
        }
        if (controls || remote != null || notice?.needsAttention() == true || (lanState.role == "client" && lanState.ready && pairedCapture.phase in setOf("preparing", "recording", "paused", "finalizing"))) Column(Modifier.align(Alignment.TopStart).fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (remote != null) RemoteBanner(remote)
            notice?.let { PrompterGenerationNotice(it) }
            if (lanState.role == "client" && lanState.ready) {
                val phase = when (pairedCapture.phase) {
                    "idle" -> "Kamera bereit"
                    "preparing" -> "Aufnahme startet …"
                    "recording" -> "Aufnahme läuft"
                    "paused" -> "Aufnahme pausiert"
                    "finalizing" -> "Aufnahme wird gespeichert …"
                    "ready" -> "Aufnahme lokal gespeichert"
                    "partial" -> "Teilaufnahme lokal gespeichert"
                    "failed" -> "Aufnahme fehlgeschlagen"
                    else -> "Kamerastatus wird ermittelt …"
                }
                Text(phase + if (pairedCapture.pending) " · Befehl ausstehend" else "", modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(Color(0xe6202329)).padding(horizontal = 14.dp, vertical = 9.dp), color = if (pairedCapture.phase == "recording") Color(0xffffb2b2) else Color.White, style = MaterialTheme.typography.labelMedium)
                if (controls) Text(if (cloudProjectLinked) "Studio-Skript zugeordnet · Speicherung auf der Kamera" else "Lokales Skript · Aufnahme bleibt zunächst auf der Kamera", modifier = Modifier.background(Color(0xe6202329)).padding(horizontal = 10.dp, vertical = 5.dp), color = Color.White, style = MaterialTheme.typography.bodySmall)
                pairedCapture.message?.let { Text(it, modifier = Modifier.background(Color(0xe6202329)).padding(10.dp), color = Color.White, style = MaterialTheme.typography.bodySmall) }
                if (controls) Text(when {
                    pairedCapture.cameraSwitching -> "Kamera wird gewechselt …"
                    pairedCapture.phase in setOf("preparing", "recording", "paused", "finalizing") -> "Kamerawechsel während Aufnahme gesperrt"
                    pairedCapture.cameraFront == true -> "Frontkamera"
                    pairedCapture.cameraFront == false -> "Rückkamera"
                    else -> "Kamerastatus wird ermittelt …"
                }, modifier = Modifier.background(Color(0xe6202329)).padding(horizontal = 10.dp, vertical = 5.dp), color = Color.White, style = MaterialTheme.typography.bodySmall)
                pairedCapture.cameraError?.let { Text(it, modifier = Modifier.background(Color(0xe6202329)).padding(10.dp), color = Color.White, style = MaterialTheme.typography.bodySmall) }
            }
            if (controls) Text(
                if (lanState.role.isEmpty()) "Offline-Prompter" else lanState.status + (lanState.rttMs?.let { " · RTT $it ms" } ?: ""),
                modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(Color(0xe6202329)).padding(horizontal = 14.dp, vertical = 9.dp),
                color = Color.White, style = MaterialTheme.typography.labelMedium
            )
        }
        if (focusLine) Box(Modifier.align(Alignment.CenterStart).width(22.dp).height(3.dp).background(Red))
        if (remaining > 0) Text("$remaining", Modifier.align(Alignment.Center), color = Red, fontSize = 100.sp, fontWeight = FontWeight.Black)
        if (controls) Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp).clip(RoundedCornerShape(24.dp)).background(Color(0xf022252c)).padding(horizontal = 8.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
          if (lanState.role == "client" && lanState.ready) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            val toggleLabel = when (pairedCapture.phase) { "recording" -> "Aufnahme pausieren"; "paused" -> "Aufnahme fortsetzen"; else -> "Aufnahme starten" }
            val canToggle = pairedCapture.phase in setOf("idle", "ready", "partial", "failed", "recording", "paused") && !pairedCapture.pending
            Button(onClick = onCaptureToggle, enabled = canToggle, modifier = Modifier.weight(1f).heightIn(min = 48.dp), shape = RoundedCornerShape(17.dp)) { Text(toggleLabel) }
            TextButton(onClick = onCaptureStop, enabled = !pairedCapture.pending && pairedCapture.phase in setOf("preparing", "recording", "paused"), modifier = Modifier.heightIn(min = 48.dp)) { Text("Stopp", color = Color.White) }
            CameraSwitchControl(enabled = pairedCapture.cameraCanSwitch && !pairedCapture.pending && !pairedCapture.cameraSwitching && pairedCapture.phase in setOf("idle", "ready", "partial", "failed"), onClick = onCameraSwitch)
          }
          Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = back, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Editor", color = Color.White) }
            TextButton(onClick = { playing = false; countdownJob?.cancel(); remaining = 0; scope.launch { scroll.scrollTo(0) } }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Anfang", color = Color.White) }
            Button(onClick = {
                if (playing || remaining > 0) { countdownJob?.cancel(); playing = false; remaining = 0 }
                else { countdownJob = scope.launch { remaining = countdownSeconds; while (remaining > 0) { delay(1000); remaining-- }; playing = true } }
            }, modifier = Modifier.weight(1f).heightIn(min = 48.dp), shape = RoundedCornerShape(17.dp)) { Text(if (playing || remaining > 0) "Pause" else "Start") }
          }
        }
    }
}
