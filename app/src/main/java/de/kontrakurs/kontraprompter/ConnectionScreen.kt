package de.kontrakurs.kontraprompter

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
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
