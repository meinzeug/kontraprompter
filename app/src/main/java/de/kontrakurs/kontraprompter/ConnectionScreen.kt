package de.kontrakurs.kontraprompter

import android.app.Activity
import android.Manifest
import android.content.pm.PackageManager
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import de.kontrakurs.kontraprompter.network.LanState
import de.kontrakurs.kontraprompter.video.LiveSession
import de.kontrakurs.kontraprompter.video.RtcVideo
import de.kontrakurs.kontraprompter.video.VideoView
import de.kontrakurs.kontraprompter.video.LocalRecording
import de.kontrakurs.kontraprompter.video.isActive
import de.kontrakurs.kontraprompter.video.recordingStopDescription
import de.kontrakurs.kontraprompter.camera.CAMERA_REMOTE_UI_ENABLED
import de.kontrakurs.kontraprompter.camera.CameraRemoteRuntime
import de.kontrakurs.kontraprompter.cloud.CloudUploadViewModel
import de.kontrakurs.kontraprompter.cloud.EXPERIMENTAL_LIVE_UPLOAD
import de.kontrakurs.kontraprompter.video.RecordingOrigin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun LivePreview(video: RtcVideo, horizontal: Boolean = false, vertical: Boolean = false, rotation: Int = 0, fill: Boolean = false, opacity: Float = 1f, modifier: Modifier = Modifier) {
    key(video) {
        AndroidView(
            factory = { context ->
                FrameLayout(context).apply {
                    video.createPreview(context)?.let { view ->
                        tag = view
                        addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
                        video.attach(view)
                    }
                }
            },
            update = { layout -> (layout.tag as? VideoView)?.setFill(fill) },
            onRelease = { layout ->
                (layout.tag as? VideoView)?.let { view ->
                    video.detach(view)
                    layout.removeView(view)
                    video.releasePreview(view)
                }
            },
            modifier = modifier.graphicsLayer {
                scaleX = if (horizontal) -1f else 1f
                scaleY = if (vertical) -1f else 1f
                rotationZ = rotation.toFloat()
                alpha = opacity
            },
        )
    }
}

@Composable
internal fun ConnectionScreen(session: LiveSession, cloud: CloudUploadViewModel, onClose: () -> Unit, onScripts: () -> Unit) {
    val state by session.lan.state.collectAsState()
    val video by session.video.collectAsState()
    val secureActivity = LocalContext.current as Activity
    // Pairing data is a bearer secret, including when presented as a QR image.
    DisposableEffect(secureActivity) {
        val alreadySecure = secureActivity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
        secureActivity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { if (!alreadySecure) secureActivity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
    if (session.host) {
        CameraConnectionScreen(session, state, video, cloud, onClose)
        return
    }
    var address by rememberSaveable { mutableStateOf("") }
    var port by rememberSaveable { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    LaunchedEffect(state.authenticated) { if (state.authenticated) token = "" }
    val activity = LocalContext.current as Activity
    AppPage(
        title = if (session.host) "Deine Kamera" else "Kamera verbinden",
        eyebrow = if (session.host) "LIVE-VORSCHAU" else "VERBINDUNG",
        description = when {
            state.status == "Videoverbindung hergestellt" && session.host -> "Deine Kamera ist mit dem Teleprompter verbunden."
            state.status == "Videoverbindung hergestellt" -> "Das Kamerabild steht für deinen Teleprompter bereit."
            state.error != null -> "Prüfe den Verbindungsstatus und das Netzwerk."
            state.ready -> "Die Videoverbindung wird vorbereitet."
            state.authenticated -> "Prüfe und bestätige die Gegenstelle."
            session.host -> "Verbinde dieses Gerät mit deinem Teleprompter."
            else -> "Wähle das Kameragerät im selben Netzwerk aus."
        },
        onBack = onClose, backLabel = "Trennen", showCamera = false
    ) {
        StatusPanel(state.status, detail = state.rttMs?.let { "Signalisierungs-RTT: $it ms · keine Bildlatenz" }, error = state.error, active = state.ready)
        if (video != null && session.host) {
            Text("Kameravorschau", style = MaterialTheme.typography.titleMedium)
            Box(Modifier.fillMaxWidth().height(240.dp).clip(RoundedCornerShape(22.dp)).background(Color.Black), contentAlignment = Alignment.Center) {
                LivePreview(video!!, modifier = Modifier.fillMaxSize())
            }
        }
        if (session.host && !state.authenticated && state.token.isNotEmpty()) {
            AppPanel {
                Text("Sitzungscode", style = MaterialTheme.typography.titleLarge)
                Text("Gib diesen Code auf dem Anzeigegerät ein. Er gilt höchstens fünf Minuten und nur für eine Verbindung.", color = SoftText, style = MaterialTheme.typography.bodyMedium)
                Text(state.token.chunked(4).joinToString(" ").uppercase(), style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text("Adresse: ${state.addresses.joinToString().ifBlank { "Keine IPv4-Adresse gefunden" }}\nPort: ${state.port}", style = MaterialTheme.typography.bodyMedium)
            }
            Text("Beide Geräte müssen dasselbe lokale Netz erreichen.", color = SoftText, style = MaterialTheme.typography.bodySmall)
        } else if (!session.host && !state.authenticated) {
            AppPanel {
                Text("Kameragerät", style = MaterialTheme.typography.titleLarge)
                PairingQrScanner { pairing ->
                    address = pairing.address
                    port = pairing.port.toString()
                    token = pairing.token
                    session.lan.connect(pairing.address, pairing.port, pairing.token)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text("Oder manuell verbinden", style = MaterialTheme.typography.titleSmall)
                // Keep the search row in place when a device appears, so focused fields do not jump.
                Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.devices.isEmpty()) Text("Suche läuft …", color = SoftText, style = MaterialTheme.typography.bodyMedium)
                    state.devices.forEach { device ->
                        FilledTonalButton(onClick = { session.lan.resolve(device) { host, resolvedPort -> activity.runOnUiThread { address = host; port = resolvedPort.toString() } } }, modifier = Modifier.heightIn(min = 48.dp)) { Text(device.name) }
                    }
                }
                OutlinedTextField(value = address, onValueChange = { address = it }, label = { Text("IP-Adresse") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = port, onValueChange = { port = it }, label = { Text("Port") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = token, onValueChange = { token = it }, label = { Text("Sitzungscode") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                MainAction("Verbinden", enabled = state.status != "Verbinde …") { session.lan.connect(address, port.toIntOrNull() ?: 0, token) }
            }
        }
        if (state.authenticated && session.host && state.localApproved && state.remoteApproved) {
            Text("Verbunden mit ${state.remoteName}", color = SoftText, style = MaterialTheme.typography.bodyMedium)
        } else if (state.authenticated) {
            AppPanel {
                Text("Gegenstelle", style = MaterialTheme.typography.labelLarge, color = SoftText)
                Text(state.remoteName, style = MaterialTheme.typography.titleLarge)
                Text(if (state.remoteApproved) "Die Gegenstelle hat bestätigt." else "Warte auf die Bestätigung der Gegenstelle.", color = SoftText, style = MaterialTheme.typography.bodyMedium)
                if (state.localApproved) Text("Auf diesem Gerät bestätigt", color = Color(0xffa3dfbb), style = MaterialTheme.typography.labelLarge)
                else MainAction("Kopplung bestätigen") { session.lan.approve() }
            }
        }
        if (state.ready && !session.host) MainAction("Zu Skripten und Prompter", onClick = onScripts)
        Text("Hier siehst du das Livebild. Die Aufnahme erfolgt am Kameragerät; Ton wird nicht live übertragen.", color = SoftText, style = MaterialTheme.typography.bodySmall)
        QuietAction("Trennen und zur Rollenwahl", onClick = onClose)
    }
}



/** Camera credentials stay on this phone and are never sent to the paired display. */
@Composable
private fun CameraCloudPanel(cloud: CloudUploadViewModel) {
    val scope = rememberCoroutineScope()
    val status by cloud.lanAutoUploader.status.collectAsState()
    val liveStatus by cloud.liveUploader.status.collectAsState()
    var expanded by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    AppPanel {
        Text("Videomaker-Cloud", style = MaterialTheme.typography.titleMedium)
        Text(status.message.ifBlank { "Fertige Aufnahmen werden bei aktiver Anmeldung und geöffneter App zur Übertragung geprüft. Das Original bleibt auf diesem Gerät." }, color = SoftText, style = MaterialTheme.typography.bodyMedium)
        if (EXPERIMENTAL_LIVE_UPLOAD && liveStatus.message.isNotBlank()) {
            Text(liveStatus.message, color = SoftText, style = MaterialTheme.typography.bodyMedium)
            val liveId = liveStatus.recordingId
            if (liveStatus.reauthorizeRequired && liveId != null) QuietAction("Live-Upload erneut freigeben", enabled = !busy) {
                scope.launch {
                    busy = true; notice = null
                    try { notice = if (cloud.liveUploader.reauthorize(liveId)) "Freigabe gespeichert." else "Freigabe nicht möglich. Das Original bleibt lokal." }
                    catch (error: CancellationException) { throw error }
                    catch (_: Exception) { notice = "Freigabe nicht bestätigt. Das Original bleibt lokal." }
                    finally { busy = false }
                }
            }
        }
        notice?.let { Text(it, color = SoftText, style = MaterialTheme.typography.bodySmall) }
        val id = status.recordingId
        if (status.reauthorizeRequired && id != null) QuietAction("Upload für diese Aufnahme erneut freigeben", enabled = !busy) {
            scope.launch {
                busy = true; notice = null
                try { notice = if (cloud.lanAutoUploader.reauthorize(id)) "Freigabe gespeichert. Der Upload wird erneut geprüft." else "Freigabe nicht möglich. Bitte Anmeldung und Skript prüfen." }
                catch (error: CancellationException) { throw error }
                catch (_: Exception) { notice = "Freigabe nicht bestätigt. Das Original bleibt lokal." }
                finally { busy = false }
            }
        }
        QuietAction(if (expanded) "Anmeldung schließen" else "Bei Videomaker anmelden", enabled = !busy) { expanded = !expanded; if (!expanded) password = "" }
        if (expanded) {
            OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("Studio-Passwort") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
            MainAction(if (busy) "Melde an …" else "Anmelden", enabled = !busy && password.isNotEmpty()) {
                val entered = password; password = ""; busy = true; notice = null
                scope.launch {
                    try { cloud.client.login(entered); expanded = false; notice = "Angemeldet. Ausstehende Aufnahmen werden geprüft." }
                    catch (error: CancellationException) { throw error }
                    catch (_: Exception) { notice = "Anmeldung nicht möglich. Die Aufnahme bleibt lokal." }
                    finally { busy = false }
                }
            }
        }
    }
}

/** Camera-first workspace: pairing and transport state remain visible without implying a recording. */
@Composable
private fun CameraConnectionScreen(session: LiveSession, state: LanState, video: RtcVideo?, cloud: CloudUploadViewModel, onClose: () -> Unit) {
    val context = LocalContext.current
    val recording by session.recording.collectAsState()
    var permissionMessage by remember { mutableStateOf<String?>(null) }
    val microphonePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            permissionMessage = if (session.startRecording(origin = RecordingOrigin.LAN_UNASSIGNED)) null else "Die Kamera ist noch nicht bereit."
        } else permissionMessage = "Für eine Aufnahme mit Ton ist die Mikrofonfreigabe erforderlich."
    }
    val connected = state.status == "Videoverbindung hergestellt" && state.error == null
    val stateColor = when {
        state.error != null -> MaterialTheme.colorScheme.error
        connected -> Color(0xff91d6b2)
        else -> Color(0xffffcd8a)
    }
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        val previewHeight = (maxHeight * 0.43f).coerceIn(210.dp, 380.dp)
        Column(
            Modifier.fillMaxHeight().widthIn(max = 640.dp).fillMaxWidth()
                .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("KONTRAPROMPTER", color = SoftText, style = MaterialTheme.typography.labelSmall, letterSpacing = 1.sp)
                    Text("Deine Kamera", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                }
                TextButton(onClick = onClose, enabled = !recording.isActive() || recording is LocalRecording.Failed, modifier = Modifier.heightIn(min = 48.dp)) { Text("Trennen") }
            }
            AppPanel {
                Text("Aufnahme", style = MaterialTheme.typography.titleMedium)
                when (val current = recording) {
                    LocalRecording.Idle -> Text("Video mit Mikrofon auf diesem Gerät aufnehmen.", color = SoftText, style = MaterialTheme.typography.bodyMedium)
                    is LocalRecording.Preparing -> Text("Aufnahme wird vorbereitet …", color = SoftText)
                    is LocalRecording.Recording -> Text(if (connected) "Aufnahme läuft · Video und Ton" else "Aufnahme läuft ohne Videoverbindung weiter.", color = Color(0xffffb2b2))
                    is LocalRecording.Paused -> Text("Aufnahme pausiert · Video und Ton werden nicht aufgezeichnet.", color = Color(0xffffcd8a))
                    is LocalRecording.Finalizing -> Text("Video wird sicher gespeichert …", color = SoftText)
                    is LocalRecording.Ready -> Text("Aufnahme lokal gespeichert · ${current.durationUs / 1_000_000} Sek. Unter ‚Aufnahmen‘ kannst du sie exportieren.", color = Color(0xffa3dfbb))
                    is LocalRecording.Partial -> Text("Aufnahme unvollständig: ${current.message} Ein geprüftes Teilvideo ist unter ‚Aufnahmen‘ exportierbar.", color = Color(0xffffcd8a))
                    is LocalRecording.Failed -> Text("Aufnahme fehlgeschlagen: ${current.message}", color = MaterialTheme.colorScheme.error)
                }
                (recording as? LocalRecording.Ready)?.let { current -> recordingStopDescription(current.reason)?.let { Text(it, color = Color(0xffffcd8a)) } }
                if (recording is LocalRecording.Preparing || recording is LocalRecording.Recording || recording is LocalRecording.Paused) {
                    if (recording is LocalRecording.Recording) QuietAction("Aufnahme pausieren") { if (!session.pauseRecording()) permissionMessage = "Pause nicht bestätigt. Bitte Status prüfen." }
                    if (recording is LocalRecording.Paused) QuietAction("Aufnahme fortsetzen") { if (!session.resumeRecording()) permissionMessage = "Fortsetzen nicht bestätigt. Die Aufnahme bleibt pausiert; du kannst sie stoppen." }
                    MainAction("Aufnahme stoppen") { session.stopRecording() }
                } else if (recording !is LocalRecording.Finalizing) {
                    MainAction("Aufnahme starten", enabled = connected && video != null && (recording as? LocalRecording.Failed)?.blocked != true) {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                            permissionMessage = if (session.startRecording(origin = RecordingOrigin.LAN_UNASSIGNED)) null else "Die Kamera ist noch nicht bereit."
                        } else microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }
                permissionMessage?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Text("Beim Verlassen der App wird die Aufnahme beendet. Abgeschlossene Videos bleiben lokal.", color = SoftText, style = MaterialTheme.typography.bodySmall)
            }
            Box(Modifier.fillMaxWidth().height(previewHeight).clip(RoundedCornerShape(24.dp))
                .background(Color(0xff08090b)), contentAlignment = Alignment.Center) {
                if (video != null) {
                    LivePreview(video, modifier = Modifier.fillMaxSize())
                } else {
                    Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Canvas(Modifier.size(44.dp)) {
                            val stroke = 2.dp.toPx()
                            drawRoundRect(Color(0xff83909d), Offset(size.width * .05f, size.height * .23f), Size(size.width * .63f, size.height * .54f), CornerRadius(size.width * .13f), style = Stroke(stroke))
                            drawLine(Color(0xff83909d), Offset(size.width * .68f, size.height * .38f), Offset(size.width * .92f, size.height * .24f), stroke)
                            drawLine(Color(0xff83909d), Offset(size.width * .92f, size.height * .24f), Offset(size.width * .92f, size.height * .76f), stroke)
                            drawLine(Color(0xff83909d), Offset(size.width * .92f, size.height * .76f), Offset(size.width * .68f, size.height * .62f), stroke)
                        }
                        Text(if (state.error != null) "Vorschau nicht verfügbar" else "Vorschau wartet auf Verbindung", color = Color(0xffe3e7ed), style = MaterialTheme.typography.titleSmall)
                        Text("Nach der bestätigten Kopplung erscheint hier das Kamerabild.", color = SoftText, style = MaterialTheme.typography.bodySmall)
                    }
                }
                Box(Modifier.align(Alignment.TopStart).padding(12.dp).clip(RoundedCornerShape(50))
                    .background(Color(0xff161a20).copy(alpha = .94f)).padding(horizontal = 12.dp, vertical = 7.dp)) {
                    Text("KAMERAVORSCHAU", color = Color.White, style = MaterialTheme.typography.labelSmall, letterSpacing = .6.sp)
                }
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(36.dp).background(
                    Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .5f)))
                ))
            }
            CameraCloudPanel(cloud)
            if (CAMERA_REMOTE_UI_ENABLED) CameraRemotePanel(session, connected)
            Surface(Modifier.fillMaxWidth(), color = Panel.copy(alpha = .96f), shape = RoundedCornerShape(20.dp), border = BorderStroke(1.dp, Color(0xff343a44))) {
                Column(Modifier.padding(horizontal = 18.dp, vertical = 15.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.size(8.dp).background(stateColor, RoundedCornerShape(50)))
                        Column(Modifier.weight(1f)) {
                            Text("VERBINDUNG", color = SoftText, style = MaterialTheme.typography.labelSmall, letterSpacing = .6.sp)
                            Text(state.status, style = MaterialTheme.typography.titleSmall)
                        }
                    }
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    if (state.authenticated && state.remoteName.isNotBlank()) Text("Anzeigegerät: ${state.remoteName}", color = SoftText, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (!state.authenticated && state.token.isNotEmpty()) {
                AppPanel {
                    Text("Mit Teleprompter verbinden", style = MaterialTheme.typography.titleMedium)
                    PairingQrDisplay(state.addresses, state.port, state.token)
                    Text("Alternativ kannst du den Sitzungscode manuell eingeben.", color = SoftText, style = MaterialTheme.typography.bodyMedium)
                    Text(state.token.chunked(4).joinToString(" ").uppercase(), style = MaterialTheme.typography.headlineSmall, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Text("Adresse: ${state.addresses.joinToString().ifBlank { "Keine IPv4-Adresse gefunden" }}\nPort: ${state.port}", color = SoftText, style = MaterialTheme.typography.bodySmall)
                    Text("Beide Geräte benötigen dasselbe lokale Netzwerk. Der Code gilt höchstens fünf Minuten.", color = SoftText, style = MaterialTheme.typography.bodySmall)
                }
            } else if (state.authenticated && (!state.localApproved || !state.remoteApproved)) {
                AppPanel {
                    Text("Kopplung bestätigen", style = MaterialTheme.typography.titleMedium)
                    Text(if (state.remoteApproved) "Das Anzeigegerät hat bestätigt." else "Warte auf die Bestätigung des Anzeigegeräts.", color = SoftText, style = MaterialTheme.typography.bodyMedium)
                    if (!state.localApproved) MainAction("Auf diesem Gerät bestätigen") { session.lan.approve() }
                    else Text("Auf diesem Gerät bestätigt", color = Color(0xffa3dfbb), style = MaterialTheme.typography.labelLarge)
                }
            }
            Text("Die Aufnahme bleibt auf diesem Gerät. Ton wird nicht live übertragen.", color = SoftText, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun CameraRemotePanel(session: LiveSession, connected: Boolean) {
    val context = LocalContext.current
    val runtime = remember(session) { CameraRemoteRuntime(context, session) }
    val active by runtime.active.collectAsState()
    val status by runtime.status.collectAsState()
    val quarantined by runtime.quarantined.collectAsState()
    val conflicts by runtime.conflicts.collectAsState()
    val uploadStatus by runtime.uploadStatus.collectAsState()
    val recoveryRequired by runtime.recoveryRequired.collectAsState()
    val previewEnabled by runtime.previewEnabled.collectAsState()
    val previewStatus by runtime.previewStatus.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current
    var password by remember { mutableStateOf("") }
    var pendingPassword by remember { mutableStateOf<String?>(null) }
    var permissionError by remember { mutableStateOf(false) }
    var confirmIdentityReset by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val entered = pendingPassword; pendingPassword = null
        if (granted && entered != null) { permissionError = false; runtime.activate(entered) }
        else permissionError = true
    }
    DisposableEffect(runtime) { onDispose { runtime.dispose() } }
    DisposableEffect(runtime, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) runtime.disablePreview() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer); runtime.disablePreview() }
    }
    if (confirmIdentityReset) AlertDialog(
        onDismissRequest = { confirmIdentityReset = false },
        title = { Text("Neue Kameraidentität anlegen?") },
        text = { Text("Alte Kameraaufträge können nicht automatisch übernommen werden. Lokale Aufnahmen bleiben erhalten und sind unter ‚Aufnahmen‘ exportierbar; offene Aufträge müssen separat geklärt werden.") },
        confirmButton = { TextButton(onClick = { confirmIdentityReset = false; runtime.createNewIdentityAfterConfirmation() }) { Text("Neue Identität anlegen") } },
        dismissButton = { TextButton(onClick = { confirmIdentityReset = false }) { Text("Abbrechen") } },
    )
    AppPanel {
        Text("Websteuerung", style = MaterialTheme.typography.titleMedium)
        Text(status, color = SoftText)
        if (quarantined) Text("Ein Kamerabericht muss geprüft werden. Das betroffene Gerätejournal lässt keine neuen Starts zu.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        conflicts.take(20).forEach { report ->
            Text(if (report.phase == "partial") "Ungeklärte Teilaufnahme" else "Ungeklärte Fehlermeldung", style = MaterialTheme.typography.labelLarge)
            Text("Aufnahme ${report.recordingId}\nSkript ${report.projectId}", color = SoftText, style = MaterialTheme.typography.bodySmall)
        }
        if (conflicts.size > 20) Text("Weitere ${conflicts.size - 20} Meldungen bleiben im Gerätejournal erhalten.", color = SoftText, style = MaterialTheme.typography.bodySmall)
        if (conflicts.isNotEmpty()) Text("Sichere eine eventuell vorhandene Datei unter ‚Aufnahmen‘. Die Meldung bleibt ungeklärt; neue Starts sind gesperrt.", color = SoftText, style = MaterialTheme.typography.bodySmall)
        if (active) {
            Text("Beim Beenden der Websteuerung wird eine laufende Aufnahme gestoppt.", color = SoftText, style = MaterialTheme.typography.bodySmall)
            Text("Das Kamerabild wird nur nach deiner Freigabe an das Dashboard übertragen; die Vorschau enthält keinen Ton.", color = SoftText, style = MaterialTheme.typography.bodySmall)
            Text(previewStatus, color = SoftText, style = MaterialTheme.typography.bodySmall)
            if (previewEnabled) QuietAction("Webvorschau beenden") { runtime.disablePreview() }
            else QuietAction("Kamerabild für Dashboard freigeben") { runtime.enablePreview() }
            QuietAction("Websteuerung beenden") { runtime.deactivate() }
        } else {
            Text("Verbinde diese Kamera mit Videomaker, um Aufnahmen vom Dashboard zu steuern.", color = SoftText, style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(password, { password = it }, label = { Text("Studio-Passwort") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
            MainAction("Websteuerung aktivieren", enabled = connected && password.isNotEmpty()) {
                val entered = password; password = ""
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) runtime.activate(entered)
                else { pendingPassword = entered; permission.launch(Manifest.permission.RECORD_AUDIO) }
            }
            if (recoveryRequired) QuietAction("Neue Kameraidentität anlegen") { confirmIdentityReset = true }
            if (!connected) Text("Verbinde zuerst die Kamera mit dem Teleprompter.", color = SoftText, style = MaterialTheme.typography.bodySmall)
        }
        if (permissionError) Text("Für Aufnahmen mit Ton ist die Mikrofonfreigabe erforderlich.", color = MaterialTheme.colorScheme.error)
        if (uploadStatus.isNotBlank()) Text(uploadStatus, color = SoftText, style = MaterialTheme.typography.bodySmall)
    }
}
