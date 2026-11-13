package de.kontrakurs.kontraprompter

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import de.kontrakurs.kontraprompter.cloud.CloudUploadCoordinator
import de.kontrakurs.kontraprompter.cloud.CloudUploadState
import de.kontrakurs.kontraprompter.cloud.StudioClient
import de.kontrakurs.kontraprompter.cloud.StudioProject
import de.kontrakurs.kontraprompter.cloud.UploadJournal
import de.kontrakurs.kontraprompter.video.SavedRecording
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.platform.LocalContext

@Composable
internal fun CloudUploadScreen(recording: SavedRecording, client: StudioClient, coordinator: CloudUploadCoordinator, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by coordinator.state.collectAsState()
    var job by remember { mutableStateOf<Job?>(null) }
    var password by remember { mutableStateOf("") }
    var projects by remember { mutableStateOf<List<StudioProject>?>(null) }
    var selected by remember { mutableStateOf<StudioProject?>(null) }
    var previous by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val running = state is CloudUploadState.Running
    fun leave() { job?.cancel(); onBack() }
    BackHandler { leave() }
    LaunchedEffect(Unit) {
        try { projects = client.projects() } catch (_: Exception) { projects = null }
    }
    LaunchedEffect(selected?.id) {
        val project = selected
        previous = if (project == null) null else withContext(Dispatchers.IO) { runCatching { UploadJournal.get(context, recording, project.id)?.phase }.getOrNull() }
    }
    AppPage("Video zu Videomaker", "CLOUD-UPLOAD", "Wähle ausdrücklich das Skript, zu dem dieses Video gehören soll.", onBack = ::leave, showRemote = false) {
        Text(if (recording.partial) "Unvollständiges Teilvideo · ${recording.durationUs / 1_000_000} Sek." else "Lokale Aufnahme · ${recording.durationUs / 1_000_000} Sek.", style = MaterialTheme.typography.titleMedium)
        Text("Das Original bleibt auf diesem Gerät. Ein Cloud-Erfolg erscheint erst nach bestätigter Prüfung des Servers.", color = SoftText, style = MaterialTheme.typography.bodyMedium)
        if (projects == null) {
            AppPanel {
                Text("Bei Videomaker anmelden", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("Studio-Passwort") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, enabled = !loading, modifier = Modifier.fillMaxWidth())
                MainAction(if (loading) "Verbinde …" else "Anmelden", enabled = password.isNotEmpty() && !loading) {
                    val entered = password; password = ""
                    scope.launch {
                        loading = true; error = null
                        try { client.login(entered); projects = client.projects() }
                        catch (e: Exception) { error = e.message ?: "Anmeldung nicht möglich." }
                        finally { loading = false }
                    }
                }
            }
        } else if (selected == null) {
            Text("Skript auswählen", style = MaterialTheme.typography.titleMedium)
            if (projects!!.isEmpty()) Text("Keine Skripte vorhanden.", color = SoftText)
            projects!!.forEach { project -> ScriptTile(project.title, "Als Ziel auswählen") { selected = project } }
            QuietAction(if (loading) "Lade …" else "Skripte aktualisieren", enabled = !loading) {
                scope.launch { loading = true; error = null; try { projects = client.projects() } catch (e: Exception) { error = e.message ?: "Skripte nicht erreichbar." }; loading = false }
            }
        } else {
            val project = selected!!
            AppPanel {
                Text("Ziel: ${project.title}", style = MaterialTheme.typography.titleMedium)
                when (val status = state) {
                    is CloudUploadState.Running -> Text(if (status.sent == 0L) "Aufnahme wird geprüft …" else "Übertragen: ${(status.sent * 100 / status.total).coerceIn(0, 100)} % · Serverbestätigung ausstehend", color = SoftText)
                    is CloudUploadState.Confirmed -> if (status.projectId == project.id) Text("Übertragung für dieses Skript bestätigt. Das Original bleibt lokal.", color = MaterialTheme.colorScheme.primary)
                    is CloudUploadState.Failed -> Text(status.message, color = MaterialTheme.colorScheme.error)
                    CloudUploadState.Idle -> if (previous == "confirmed") Text("Eine frühere Übertragung wurde bestätigt.", color = SoftText) else if (previous != null) Text("Eine frühere Übertragung ist nicht bestätigt; erneut versuchen ist sicher.", color = SoftText)
                }
                if (running) QuietAction("Upload abbrechen") { job?.cancel() }
                else MainAction("Video hochladen") { job = scope.launch { coordinator.upload(recording, project.id) } }
                QuietAction("Anderes Skript wählen", enabled = !running) { selected = null }
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        Spacer(Modifier.height(8.dp))
    }
}
