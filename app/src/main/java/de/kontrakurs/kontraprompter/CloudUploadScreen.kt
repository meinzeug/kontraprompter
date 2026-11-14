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
import de.kontrakurs.kontraprompter.cloud.confirmedFor
import de.kontrakurs.kontraprompter.cloud.JournalLookup
import de.kontrakurs.kontraprompter.cloud.matchingJournal
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
    var journal by remember { mutableStateOf<JournalLookup?>(null) }
    var reauthProjectId by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val running = state is CloudUploadState.Running
    fun leave() { job?.cancel(); onBack() }
    BackHandler { leave() }
    LaunchedEffect(Unit) {
        try { projects = client.projects() } catch (_: Exception) { projects = null } finally { loading = false }
    }
    LaunchedEffect(state) {
        val status = state
        if (status is CloudUploadState.Failed && status.recordingId == recording.id && status.kind == "auth" && selected?.id == status.projectId) {
            reauthProjectId = status.projectId
            selected = null; projects = null; error = status.message
        }
    }
    LaunchedEffect(recording.id, selected?.id) {
        val project = selected
        journal = null
        if (project != null) {
            val result = withContext(Dispatchers.IO) { runCatching { UploadJournal.get(context, recording, project.id)?.phase } }
            journal = JournalLookup(recording.id, project.id, result.getOrNull(), result.isFailure)
        }
    }
    AppPage("Video zu Videomaker", "CLOUD-UPLOAD", "Wähle ausdrücklich das Skript, zu dem dieses Video gehören soll.", onBack = ::leave, showRemote = false) {
        Text(if (recording.partial) "Unvollständiges Teilvideo · ${recording.durationUs / 1_000_000} Sek." else "Lokale Aufnahme · ${recording.durationUs / 1_000_000} Sek.", style = MaterialTheme.typography.titleMedium)
        if (recording.partial) Text("Teilvideos können derzeit nur lokal exportiert werden.", color = MaterialTheme.colorScheme.error)
        Text("Das Original bleibt auf diesem Gerät. Ein Cloud-Erfolg erscheint erst nach bestätigter Prüfung des Servers.", color = SoftText, style = MaterialTheme.typography.bodyMedium)
        Text("Beim Verlassen dieser Seite oder wenn Android die App neu erstellt, wird ein laufender Upload abgebrochen. Du kannst ihn anschließend erneut versuchen.", color = SoftText, style = MaterialTheme.typography.bodyMedium)
        if (projects == null) {
            AppPanel {
                Text("Bei Videomaker anmelden", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("Studio-Passwort") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, enabled = !loading, modifier = Modifier.fillMaxWidth())
                MainAction(if (loading) "Verbinde …" else "Anmelden", enabled = password.isNotEmpty() && !loading) {
                    val entered = password; password = ""
                    scope.launch {
                        loading = true; error = null
                        try {
                            client.login(entered); val loaded = client.projects(); projects = loaded
                            val old = reauthProjectId
                            if (old != null) {
                                selected = loaded.firstOrNull { it.id == old }
                                if (selected == null) error = "Das zuvor gewählte Skript ist nicht mehr verfügbar."
                                reauthProjectId = null
                            }
                        }
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
            val currentJournal = matchingJournal(journal, recording.id, project.id)
            AppPanel {
                Text("Ziel: ${project.title}", style = MaterialTheme.typography.titleMedium)
                when (val status = state) {
                    is CloudUploadState.Running -> if (status.recordingId == recording.id && status.projectId == project.id) Text(if (status.sent == 0L) "Aufnahme wird geprüft …" else "Übertragen: ${(status.sent * 100 / status.total).coerceIn(0, 100)} % · Serverbestätigung ausstehend", color = SoftText) else Text("Ein anderer Upload wird noch beendet.", color = SoftText)
                    is CloudUploadState.Confirmed -> if (confirmedFor(status, recording.id, project.id)) Text("Übertragung für dieses Skript bestätigt. Das Original bleibt lokal.", color = MaterialTheme.colorScheme.primary)
                    is CloudUploadState.Failed -> if (status.recordingId == recording.id && status.projectId == project.id) Text(status.message, color = MaterialTheme.colorScheme.error)
                    CloudUploadState.Idle -> if (currentJournal?.phase == "confirmed") Text("Eine frühere Übertragung wurde bestätigt.", color = SoftText) else if (currentJournal?.phase != null) Text("Eine frühere Übertragung ist nicht bestätigt; erneut versuchen ist sicher.", color = SoftText)
                }
                if (running) QuietAction("Upload abbrechen") { job?.cancel() }
                else MainAction("Video hochladen", enabled = currentJournal != null && !currentJournal.failed && !recording.partial) { if (job?.isActive != true) job = scope.launch { coordinator.upload(recording, project.id) } }
                if (currentJournal == null) Text("Uploadstatus wird geprüft …", color = SoftText)
                if (currentJournal?.failed == true) Text("Der Uploadstatus ist beschädigt. Bitte exportiere die lokale Aufnahme; es wird nichts überschrieben.", color = MaterialTheme.colorScheme.error)
                QuietAction("Anderes Skript wählen", enabled = !running) { selected = null }
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        Spacer(Modifier.height(8.dp))
    }
}
