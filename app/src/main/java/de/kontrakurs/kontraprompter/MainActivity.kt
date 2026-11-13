package de.kontrakurs.kontraprompter

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import de.kontrakurs.kontraprompter.network.LanState
import de.kontrakurs.kontraprompter.video.LiveSession
import de.kontrakurs.kontraprompter.video.RtcVideo
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val Red = Color(0xffe50914)
private val Background = Color(0xff0d0d0d)
private val Surface = Color(0xff1b1b1b)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Red, background = Background, surface = Surface, onPrimary = Color.White)) {
                KontraApp(this)
            }
        }
    }
}

@Composable
private fun KontraApp(activity: ComponentActivity) {
    val repository = remember { ScriptRepository(activity) }
    val preferences = remember { activity.getSharedPreferences("prompter_settings", Activity.MODE_PRIVATE) }
    var scripts by remember { mutableStateOf(repository.load().ifEmpty { listOf(Script()) }) }
    var selectedId by rememberSaveable { mutableStateOf(repository.currentId ?: scripts.first().id) }
    val selected = scripts.find { it.id == selectedId } ?: scripts.first()
    var page by rememberSaveable { mutableStateOf("roles") }
    var session by remember { mutableStateOf<LiveSession?>(null) }
    val emptyVideo = remember { kotlinx.coroutines.flow.MutableStateFlow<RtcVideo?>(null) }
    val video by (session?.video ?: emptyVideo).collectAsState()
    val emptyLan = remember { kotlinx.coroutines.flow.MutableStateFlow(LanState()) }
    val lanState by (session?.lan?.state ?: emptyLan).collectAsState()
    var cameraPermissionError by remember { mutableStateOf(false) }
    fun startHost() { session?.close(); session = LiveSession(activity, true); page = "connection" }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) { cameraPermissionError = false; startHost() } else cameraPermissionError = true
    }
    DisposableEffect(activity) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && session != null) {
                session?.close(); session = null
                if (page == "connection") page = "roles"
            }
        }
        activity.lifecycle.addObserver(observer)
        onDispose { activity.lifecycle.removeObserver(observer); session?.close(); session = null }
    }
    var keepAwake by rememberSaveable { mutableStateOf(preferences.getBoolean("awake", true)) }
    var fontSize by rememberSaveable { mutableFloatStateOf(preferences.getFloat("font", 44f)) }
    var speed by rememberSaveable { mutableFloatStateOf(preferences.getFloat("speed", 35f)) }
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
    fun update(script: Script) { scripts = scripts.map { if (it.id == script.id) script else it }; repository.save(scripts) }
    fun select(id: String) { selectedId = id; repository.currentId = id }
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
    Surface(modifier = Modifier.fillMaxSize(), color = Background) {
        when (page) {
            "roles" -> Page("KONTRAKURS", "KONTRAPROMPTER") {
                Text("Zwei Geräte. Ein Auftritt.", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(20.dp))
                Action("TELEPROMPTER / ANZEIGE") { page = "scripts" }
                Action("KAMERA / VORSCHAU") {
                    if (ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startHost()
                    else cameraPermission.launch(Manifest.permission.CAMERA)
                }
                if (cameraPermissionError) Text("Für die Kamera-Vorschau ist die Kameraerlaubnis erforderlich.", color = Color(0xffff7777))
                Text("Lokaler Prompter und experimentelle Live-Vorschau. Aufnahme und Ton sind noch nicht implementiert.", color = Color.LightGray)
            }
            "connection" -> session?.let { active ->
                ConnectionScreen(active, onClose = { active.close(); session = null; page = "roles" }, onScripts = { page = "scripts" })
            } ?: Page("VERBINDUNG", "NICHT VERBUNDEN") { Action("ZUR ROLLENWAHL") { page = "roles" } }
            "scripts" -> Page("SKRIPTE", "OFFLINE AUF DIESEM GERÄT") {
                scripts.forEach { script ->
                    Card(colors = CardDefaults.cardColors(containerColor = Surface), modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { select(script.id); page = "editor" }) {
                        Column(Modifier.padding(16.dp)) { Text(script.title.ifBlank { "Ohne Titel" }, style = MaterialTheme.typography.titleLarge); Text("${script.text.length} Zeichen", color = Color.LightGray) }
                    }
                }
                Action(if (session == null) "KAMERA VERBINDEN" else "VERBINDUNG ANZEIGEN") {
                    if (session == null) session = LiveSession(activity, false)
                    page = "connection"
                }
                Action("STUDIO-SKRIPTE ÖFFNEN") { page = "studio" }
                Action("NEUES SKRIPT") { val s = Script(); scripts = scripts + s; repository.save(scripts); select(s.id); page = "editor" }
                Action("ZUR ROLLENWAHL") { page = "roles" }
            }
            "studio" -> StudioScreen(onSelect = { project ->
                val copy = Script(title = project.title, text = project.text)
                scripts = scripts + copy; repository.save(scripts); select(copy.id); page = "editor"
            }, onBack = { page = "scripts" })
            "editor" -> Page("SKRIPT BEARBEITEN", "ÄNDERUNGEN WERDEN LOKAL GESPEICHERT") {
                OutlinedTextField(value = selected.title, onValueChange = { update(selected.copy(title = it)) }, label = { Text("Titel") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = selected.text, onValueChange = { update(selected.copy(text = it)) }, label = { Text("Skripttext") }, modifier = Modifier.fillMaxWidth().height(260.dp))
                Action("TXT / MD IMPORTIEREN") { importer.launch(arrayOf("text/*", "application/octet-stream")) }
                Action("PROMPTER STARTEN") { page = "prompter" }
                Action("EINSTELLUNGEN") { page = "settings" }
                Action("KALIBRIERUNG") { page = "calibration" }
                Action("SKRIPTLISTE") { page = "scripts" }
            }
            "settings" -> Page("EINSTELLUNGEN", "PROMPTER") {
                SettingSlider("Schriftgröße", fontSize, 20f..100f, "${fontSize.toInt()} sp") { fontSize = it }
                SettingSlider("Scrolltempo", speed, 5f..160f, "${speed.toInt()} dp/s") { speed = it }
                SettingSlider("Textbreite", width, .35f..1f, "${(width * 100).toInt()} %") { width = it }
                SettingSlider("Zeilenabstand", lineSpacing, 1f..2f, "${(lineSpacing * 100).toInt()} %") { lineSpacing = it }
                SettingSlider("Texthintergrund", backgroundOpacity, 0f..1f, "${(backgroundOpacity * 100).toInt()} %") { backgroundOpacity = it }
                SettingSlider("Displayhelligkeit", brightness, .05f..1f, "${(brightness * 100).toInt()} %") { brightness = it }
                Toggle("Video horizontal spiegeln", videoH) { videoH = it }
                Toggle("Video vertikal spiegeln", videoV) { videoV = it }
                Toggle("Video füllt Bild (Beschnitt)", videoFill) { videoFill = it }
                Action("VIDEO DREHEN: $videoRotation°") { videoRotation = (videoRotation + 90) % 360 }
                SettingSlider("Video-Deckkraft", videoOpacity, 0f..1f, "${(videoOpacity * 100).toInt()} %") { videoOpacity = it }
                SettingSlider("Video-Helligkeit", videoBrightness, 0f..1f, "${(videoBrightness * 100).toInt()} %") { videoBrightness = it }
                Toggle("Text horizontal spiegeln", textH) { textH = it }
                Toggle("Text vertikal spiegeln", textV) { textV = it }
                Toggle("Fettschrift", bold) { bold = it }
                Toggle("Leselinie", focusLine) { focusLine = it }
                Toggle("Bildschirm eingeschaltet lassen", keepAwake) { keepAwake = it }
                Action("TEXT DREHEN: $textRotation°") { textRotation = (textRotation + 90) % 360 }
                Action("COUNTDOWN: $countdown SEKUNDEN") { countdown = when (countdown) { 3 -> 5; 5 -> 10; else -> 3 } }
                Text("Fit zeigt das ganze Bild mit möglichen Balken. Fill füllt die Fläche und kann das Bild beschneiden.", color = Color.LightGray)
                Action("ZURÜCK ZUM EDITOR") { page = "editor" }
            }
            "calibration" -> Page("SPIEGELUNG KALIBRIEREN", "AM SPIEGELGLAS AUSRICHTEN") {
                Text("Prüfe die Lesbarkeit im tatsächlichen Aufbau. Glas und Geräteausrichtung können unterschiedliche Einstellungen erfordern.")
                Box(Modifier.fillMaxWidth().height(220.dp).background(Color.Black), contentAlignment = Alignment.Center) {
                    Text("↑ OBEN\nL ←   → R\nUNTEN ↓", modifier = Modifier.graphicsLayer { scaleX = if (textH) -1f else 1f; scaleY = if (textV) -1f else 1f; rotationZ = textRotation.toFloat() }, color = Color.White, fontSize = 30.sp, textAlign = TextAlign.Center)
                }
                Toggle("Text horizontal spiegeln", textH) { textH = it }
                Toggle("Text vertikal spiegeln", textV) { textV = it }
                Action("DREHEN: $textRotation°") { textRotation = (textRotation + 90) % 360 }
                Action("ZURÜCK") { page = "editor" }
            }
            "prompter" -> Prompter(selected.text, fontSize, speed, width, lineSpacing, textH, textV, textRotation, backgroundOpacity, focusLine, bold, countdown, savedPosition, { savedPosition = it }, { page = "editor" }, video, videoH, videoV, videoRotation, videoFill, videoOpacity * videoBrightness, lanState)
        }
    }
}

@Composable
private fun Page(title: String, eyebrow: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(eyebrow, color = Red, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        Text(title, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Black)
        Spacer(Modifier.height(8.dp))
        content()
        Spacer(Modifier.height(24.dp))
    }
}
@Composable private fun Action(label: String, onClick: () -> Unit) { Button(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = RoundedCornerShape(8.dp)) { Text(label, fontWeight = FontWeight.Bold) } }
@Composable private fun Toggle(label: String, value: Boolean, change: (Boolean) -> Unit) { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) { Text(label, modifier = Modifier.weight(1f)); Switch(checked = value, onCheckedChange = change) } }
@Composable private fun SettingSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, display: String, change: (Float) -> Unit) { Column { Text("$label: $display"); Slider(value = value, onValueChange = change, valueRange = range) } }

@Composable
private fun Prompter(text: String, fontSize: Float, speed: Float, width: Float, lineSpacing: Float, horizontal: Boolean, vertical: Boolean, rotation: Int, opacity: Float, focusLine: Boolean, bold: Boolean, countdownSeconds: Int, initialPosition: Int, savePosition: (Int) -> Unit, back: () -> Unit, video: RtcVideo?, videoH: Boolean, videoV: Boolean, videoRotation: Int, videoFill: Boolean, videoOpacity: Float, lanState: LanState) {
    val scroll = rememberScrollState(initialPosition)
    val density = LocalDensity.current.density
    val scope = rememberCoroutineScope()
    var playing by remember { mutableStateOf(false) }
    var controls by remember { mutableStateOf(true) }
    var remaining by remember { mutableIntStateOf(0) }
    var countdownJob by remember { mutableStateOf<Job?>(null) }
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
        if (controls) Text(
            if (lanState.role.isEmpty()) "OFFLINE-PROMPTER" else lanState.status + (lanState.rttMs?.let { " · RTT $it ms" } ?: ""),
            modifier = Modifier.align(Alignment.TopStart).background(Color(0xbb191919)).padding(8.dp),
            color = Color.White
        )
        if (focusLine) Box(Modifier.align(Alignment.CenterStart).width(22.dp).height(3.dp).background(Red))
        if (remaining > 0) Text("$remaining", Modifier.align(Alignment.Center), color = Red, fontSize = 100.sp, fontWeight = FontWeight.Black)
        if (controls) Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0xdd191919)).padding(8.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = back) { Text("EDITOR") }
            TextButton(onClick = { playing = false; scope.launch { scroll.scrollTo(0) } }) { Text("ANFANG") }
            Button(onClick = {
                if (playing || remaining > 0) { countdownJob?.cancel(); playing = false; remaining = 0 }
                else { countdownJob = scope.launch { remaining = countdownSeconds; while (remaining > 0) { delay(1000); remaining-- }; playing = true } }
            }) { Text(if (playing || remaining > 0) "PAUSE" else "START") }
        }
    }
}
