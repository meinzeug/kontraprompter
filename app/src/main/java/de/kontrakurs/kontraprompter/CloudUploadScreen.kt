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
import de.kontrakurs.kontraprompter.cloud.MANUAL_UPLOAD_CONTINUATION_ENABLED
import de.kontrakurs.kontraprompter.cloud.ManualResumeDecision
import de.kontrakurs.kontraprompter.cloud.ManualUploadContinuation
import de.kontrakurs.kontraprompter.cloud.showCoordinatorConfirmation
import de.kontrakurs.kontraprompter.cloud.JournalLookup
import de.kontrakurs.kontraprompter.cloud.matchingJournal
import de.kontrakurs.kontraprompter.cloud.RECORDING_DELETED
import de.kontrakurs.kontraprompter.cloud.RECORDING_DELETED_MESSAGE
import de.kontrakurs.kontraprompter.video.SavedRecording
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.platform.LocalContext

@Composable
internal fun CloudUploadScreen(recording: SavedRecording, client: StudioClient, coordinator: CloudUploadCoordinator, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val continuation = remember { ManualUploadContinuation(context, client, coordinator) }
    val state by coordinator.state.collectAsState()
    var job by remember { mutableStateOf<Job?>(null) }
    var password by remember { mutableStateOf("") }
    var projects by remember { mutableStateOf<List<StudioProject>?>(null) }
    var selected by remember { mutableStateOf<StudioProject?>(null) }
    var journal by remember { mutableStateOf<JournalLookup?>(null) }
    var reauthProjectId by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var autoResume by remember(recording.id) { mutableStateOf(false) }
    var autoBusy by remember(recording.id) { mutableStateOf(false) }
    var resumeChecked by remember(recording.id) { mutableStateOf(false) }
    var selectionRevision by remember(recording.id) { mutableIntStateOf(0) }
    var continuationMessage by remember(recording.id) { mutableStateOf<String?>(null) }
    val running = state is CloudUploadState.Running || autoBusy
    fun decisionMessage(decision: ManualResumeDecision): String? = when (decision) {
        ManualResumeDecision.START -> null
        ManualResumeDecision.COMPLETE -> "Übertragung für dieses Skript bestätigt. Das Original bleibt lokal."
        ManualResumeDecision.HISTORICAL_CONFIRMATION -> "Eine frühere Übertragung dieser Aufnahme wurde bestätigt. Der aktuelle Cloudbestand und die lokale Datei wurden nicht erneut geprüft."
        ManualResumeDecision.PAUSED -> "Der Fortsetzungsversuch wurde nicht gestartet. Bereits gesendete Daten können trotzdem angekommen sein."
        ManualResumeDecision.NO_SESSION -> "Bitte erneut bei Videomaker anmelden; das Original bleibt lokal."
        ManualResumeDecision.SESSION_CHANGED -> "Die Sitzung ist neu. Bitte dieses Ziel erneut ausdrücklich bestätigen und den Upload starten."
        ManualResumeDecision.PROJECT_MISSING -> "Das ursprüngliche Skript ist nicht mehr verfügbar. Bitte Ziel ausdrücklich neu wählen."
        ManualResumeDecision.RECORDING_CHANGED -> "Aufnahme oder Uploadauftrag stimmen nicht überein. Das Original bleibt lokal."
        ManualResumeDecision.REMOTE_RECORDING -> "Fernaufnahmen nutzen ihren eigenen Uploadweg."
        ManualResumeDecision.BLOCKED -> "Upload nicht bestätigt oder angehalten. Das Original bleibt lokal."
    }
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
            val result = withContext(Dispatchers.IO) { runCatching { UploadJournal.get(context, recording, project.id) } }
            journal = JournalLookup(recording.id, project.id, result.getOrNull()?.phase, result.isFailure, result.getOrNull()?.conflictReason)
        }
    }
    // Deliberately disabled in production: only resume an earlier explicitly
    // opted-in destination after re-login and a fresh project-list check.
    LaunchedEffect(recording.id, projects) {
        val available = projects ?: return@LaunchedEffect
        if (!MANUAL_UPLOAD_CONTINUATION_ENABLED || resumeChecked) return@LaunchedEffect
        resumeChecked = true
        val observedRevision = selectionRevision
        val found = withContext(Dispatchers.IO) { runCatching { continuation.pending(recording.id) } }
        if (selectionRevision != observedRevision) return@LaunchedEffect
        if (found.isFailure) { error = "Uploadauftrag ist nicht lesbar. Die Aufnahme bleibt lokal."; return@LaunchedEffect }
        val pending = found.getOrNull() ?: return@LaunchedEffect
        if (pending.state != "pending") return@LaunchedEffect
        val target = available.firstOrNull { it.id == pending.projectId }
        if (target == null) { continuationMessage = "Das ursprüngliche Skript ist nicht verfügbar. Bitte Ziel ausdrücklich neu wählen."; return@LaunchedEffect }
        selected = target; autoResume = true
        if (job?.isActive != true) job = scope.launch {
            autoBusy = true
            continuationMessage = "Upload wird fortgesetzt …"
            try { continuationMessage = decisionMessage(continuation.resume(recording)) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { continuationMessage = "Upload nicht bestätigt. Das Original bleibt lokal." }
            finally { autoBusy = false }
        }
    }
    AppPage("Video zu Videomaker", "CLOUD-UPLOAD", "Wähle ausdrücklich das Skript, zu dem dieses Video gehören soll.", onBack = ::leave, showRemote = false) {
        Text(if (recording.partial) "Unvollständiges Teilvideo · ${recording.durationUs / 1_000_000} Sek." else "Lokale Aufnahme · ${recording.durationUs / 1_000_000} Sek.", style = MaterialTheme.typography.titleMedium)
        if (recording.partial) Text("Teilvideos können derzeit nur lokal exportiert werden.", color = MaterialTheme.colorScheme.error)
        Text("Das Original bleibt auf diesem Gerät. Ein Cloud-Erfolg erscheint erst nach bestätigter Prüfung des Servers.", color = SoftText, style = MaterialTheme.typography.bodyMedium)
        Text(if (MANUAL_UPLOAD_CONTINUATION_ENABLED) "Beim Verlassen wird der Upload unterbrochen. Ein ausdrücklich aktivierter Auftrag kann nach Anmeldung beim erneuten Öffnen dieser Seite fortgesetzt werden." else "Beim Verlassen dieser Seite oder wenn Android die App neu erstellt, wird ein laufender Upload abgebrochen. Du kannst ihn anschließend erneut versuchen.", color = SoftText, style = MaterialTheme.typography.bodyMedium)
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
            projects!!.forEach { project -> ScriptTile(project.title, "Als Ziel auswählen") { selectionRevision++; selected = project } }
            QuietAction(if (loading) "Lade …" else "Skripte aktualisieren", enabled = !loading) {
                scope.launch { loading = true; error = null; try { projects = client.projects() } catch (e: Exception) { error = e.message ?: "Skripte nicht erreichbar." }; loading = false }
            }
        } else {
            val project = selected!!
            val currentJournal = matchingJournal(journal, recording.id, project.id)
            AppPanel {
                Text("Ziel: ${project.title}", style = MaterialTheme.typography.titleMedium)
                val lastFailure = (state as? CloudUploadState.Failed)?.takeIf { it.recordingId == recording.id && it.projectId == project.id }
                val deleted = currentJournal?.conflictReason == RECORDING_DELETED || lastFailure?.kind == RECORDING_DELETED
                if (currentJournal?.conflictReason == RECORDING_DELETED && lastFailure?.kind != RECORDING_DELETED)
                    Text(RECORDING_DELETED_MESSAGE, color = MaterialTheme.colorScheme.error)
                when (val status = state) {
                    is CloudUploadState.Running -> if (status.recordingId == recording.id && status.projectId == project.id) Text(if (status.sent == 0L) "Aufnahme wird geprüft …" else "Übertragen: ${(status.sent * 100 / status.total).coerceIn(0, 100)} % · Serverbestätigung ausstehend", color = SoftText) else Text("Ein anderer Upload wird noch beendet.", color = SoftText)
                    is CloudUploadState.Confirmed -> if (showCoordinatorConfirmation(status, recording.id, project.id)) Text("Übertragung für dieses Skript bestätigt. Das Original bleibt lokal.", color = MaterialTheme.colorScheme.primary)
                    is CloudUploadState.Failed -> if (status.recordingId == recording.id && status.projectId == project.id) Text(status.message, color = MaterialTheme.colorScheme.error)
                    CloudUploadState.Idle -> if (currentJournal?.phase == "confirmed") Text("Eine frühere Übertragung wurde bestätigt.", color = SoftText) else if (currentJournal?.phase != null && !deleted) Text("Eine frühere Übertragung ist nicht bestätigt; erneut versuchen ist sicher.", color = SoftText)
                }
                if (MANUAL_UPLOAD_CONTINUATION_ENABLED && !recording.partial && recording.projectId == null && !running) {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Checkbox(checked = autoResume, onCheckedChange = { checked ->
                            if (checked) autoResume = true else scope.launch {
                                try {
                                    val paused = withContext(NonCancellable) { continuation.pause(recording.id) }
                                    autoResume = false
                                    continuationMessage = if (paused) "Fortsetzung pausiert." else "Kein ausstehender Fortsetzungsauftrag."
                                }
                                catch (_: Exception) { error = "Pausieren nicht bestätigt. Der bisherige Auftrag kann erneut erscheinen." }
                            }
                        })
                        Text("Nach Unterbrechung auf dieser Seite fortsetzen", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                continuationMessage?.let { Text(it, color = SoftText, style = MaterialTheme.typography.bodySmall) }
                if (running) QuietAction("Upload abbrechen") {
                    val runningJob = job
                    if (MANUAL_UPLOAD_CONTINUATION_ENABLED && autoResume) scope.launch(start = CoroutineStart.UNDISPATCHED) {
                        val result = runCatching { withContext(NonCancellable) { continuation.pause(recording.id) } }
                        runningJob?.cancel()
                        continuationMessage = when {
                            result.isFailure -> "Pausieren nicht bestätigt. Bereits gesendete Daten können trotzdem angekommen sein."
                            result.getOrNull() == true -> "Fortsetzung pausiert. Ein bereits angenommener Upload kann trotzdem abgeschlossen sein."
                            else -> "Kein ausstehender Fortsetzungsauftrag. Bereits gesendete Daten können trotzdem angekommen sein."
                        }
                    } else runningJob?.cancel()
                }
                else MainAction("Video hochladen", enabled = currentJournal != null && !currentJournal.failed && !recording.partial && !deleted) {
                    if (job?.isActive != true) job = scope.launch {
                        try {
                            if (MANUAL_UPLOAD_CONTINUATION_ENABLED && autoResume) {
                                autoBusy = true
                                try { continuationMessage = decisionMessage(continuation.start(recording, project.id)) }
                                finally { autoBusy = false }
                            } else coordinator.upload(recording, project.id)
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) { error = e.message?.take(220) ?: "Upload nicht bestätigt. Das Original bleibt lokal." }
                    }
                }
                if (currentJournal == null) Text("Uploadstatus wird geprüft …", color = SoftText)
                if (currentJournal?.failed == true) Text("Der Uploadstatus ist beschädigt. Bitte exportiere die lokale Aufnahme; es wird nichts überschrieben.", color = MaterialTheme.colorScheme.error)
                QuietAction("Anderes Skript wählen", enabled = !running) {
                    selectionRevision++
                    if (MANUAL_UPLOAD_CONTINUATION_ENABLED) scope.launch {
                        try {
                            val paused = withContext(NonCancellable) { continuation.pause(recording.id) }
                            continuationMessage = if (paused) "Fortsetzung pausiert." else "Kein ausstehender Fortsetzungsauftrag."
                            autoResume = false; selected = null
                        }
                        catch (_: Exception) { error = "Der bisherige Uploadauftrag konnte nicht pausiert werden." }
                    } else selected = null
                }
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        Spacer(Modifier.height(8.dp))
    }
}
