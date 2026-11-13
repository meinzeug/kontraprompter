package de.kontrakurs.kontraprompter

import android.app.Activity
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import de.kontrakurs.kontraprompter.network.LanState
import de.kontrakurs.kontraprompter.video.LiveSession
import de.kontrakurs.kontraprompter.video.RtcVideo
import de.kontrakurs.kontraprompter.video.VideoView

@Composable
fun LivePreview(video: RtcVideo, horizontal: Boolean = false, vertical: Boolean = false, rotation: Int = 0, fill: Boolean = false, opacity: Float = 1f, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val view = remember(video) { VideoView(context, video.egl.eglBaseContext) }
    DisposableEffect(video, view) {
        video.attach(view)
        onDispose { video.detach(view); view.release() }
    }
    AndroidView(factory = { view }, update = { it.setFill(fill) }, modifier = modifier.graphicsLayer {
        scaleX = if (horizontal) -1f else 1f
        scaleY = if (vertical) -1f else 1f
        rotationZ = rotation.toFloat()
        alpha = opacity
    })
}

@Composable
fun ConnectionScreen(session: LiveSession, onClose: () -> Unit, onScripts: () -> Unit) {
    val state by session.lan.state.collectAsState()
    val video by session.video.collectAsState()
    if (session.host) {
        CameraConnectionScreen(session, state, video, onClose)
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
        Text("Nur Live-Vorschau. Aufnahme und Tonübertragung sind noch nicht verfügbar.", color = SoftText, style = MaterialTheme.typography.bodySmall)
        QuietAction("Trennen und zur Rollenwahl", onClick = onClose)
    }
}


/** Camera-first workspace: pairing and transport state remain visible without implying a recording. */
@Composable
private fun CameraConnectionScreen(session: LiveSession, state: LanState, video: RtcVideo?, onClose: () -> Unit) {
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
                TextButton(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp)) { Text("Trennen") }
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
                    Text("Öffne auf dem zweiten Smartphone die Kameraverbindung und gib diesen Sitzungscode ein.", color = SoftText, style = MaterialTheme.typography.bodyMedium)
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
            Text("Nur Livebild · Aufnahme und Tonübertragung sind noch nicht verfügbar.", color = SoftText, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(12.dp))
        }
    }
}
