package de.kontrakurs.kontraprompter

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(if (session.host) "KAMERA / VORSCHAU" else "KAMERA VERBINDEN", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
        Text(state.status, color = Color.White, style = MaterialTheme.typography.titleMedium)
        state.error?.let { Text(it, color = Color(0xffff7777)) }
        state.rttMs?.let { Text("Signalisierungs-RTT: $it ms (keine Bildlatenz)", color = Color.LightGray) }
        if (session.host) {
            if (!state.authenticated && state.token.isNotEmpty()) {
                Text("Auf dem Anzeigegerät diesen Sitzungscode eingeben. Er gilt höchstens fünf Minuten und wird nach erfolgreicher Kopplung ungültig.")
                Text(state.token.chunked(4).joinToString(" ").uppercase(), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("Adresse: ${state.addresses.joinToString().ifBlank { "Keine IPv4-Adresse gefunden" }}\nPort: ${state.port}")
                Text("Beide Geräte müssen dasselbe lokale Netz erreichen. WLAN mit Geräteisolierung kann die Verbindung verhindern.", color = Color.LightGray)
            }
        } else if (!state.authenticated) {
            Text("Kameragerät aus der Suche wählen oder Adresse und Port manuell eingeben. Der Sitzungscode steht auf dem Kameragerät.")
            state.devices.forEach { device ->
                OutlinedButton(onClick = { session.lan.resolve(device) { host, resolvedPort -> activity.runOnUiThread { address = host; port = resolvedPort.toString() } } }, modifier = Modifier.fillMaxWidth()) { Text(device.name) }
            }
            OutlinedTextField(value = address, onValueChange = { address = it }, label = { Text("IP-Adresse") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = port, onValueChange = { port = it }, label = { Text("Port") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = token, onValueChange = { token = it }, label = { Text("Sitzungscode") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(onClick = { session.lan.connect(address, port.toIntOrNull() ?: 0, token) }, modifier = Modifier.fillMaxWidth(), enabled = state.status != "Verbinde …") { Text("VERBINDEN") }
        }
        if (state.authenticated) {
            Text("Gegenstelle: ${state.remoteName}", style = MaterialTheme.typography.titleLarge)
            Text(if (state.remoteApproved) "Gegenstelle hat bestätigt" else "Warte auf Bestätigung der Gegenstelle")
            Button(onClick = { session.lan.approve() }, enabled = !state.localApproved, modifier = Modifier.fillMaxWidth()) { Text(if (state.localApproved) "BESTÄTIGT" else "KOPPLUNG BESTÄTIGEN") }
        }
        if (video != null && session.host) {
            Box(Modifier.fillMaxWidth().height(240.dp).background(Color.Black), contentAlignment = Alignment.Center) {
                LivePreview(video!!, modifier = Modifier.fillMaxSize())
            }
        }
        if (state.ready && !session.host) Button(onClick = onScripts, modifier = Modifier.fillMaxWidth()) { Text("SKRIPTE UND PROMPTER") }
        Text("Nur Live-Vorschau: Eine Aufnahme oder Tonübertragung ist noch nicht implementiert.", color = Color.LightGray)
        OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("TRENNEN / ZUR ROLLENWAHL") }
    }
}
