package de.kontrakurs.kontraprompter

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import de.kontrakurs.kontraprompter.cloud.StudioClient
import de.kontrakurs.kontraprompter.cloud.StudioProject
import kotlinx.coroutines.launch

@Composable
fun StudioScreen(client: StudioClient, remoteEnabled: Boolean, remoteStatus: String, onRemote: (Boolean) -> Unit, onSelect: (StudioProject) -> Unit, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var projects by remember { mutableStateOf<List<StudioProject>?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var stale by remember { mutableStateOf(false) }
    fun load() {
        scope.launch {
            loading = true; error = null
            try { projects = client.projects(); stale = false } catch (e: Exception) { stale = projects != null; error = e.message ?: "Verbindung fehlgeschlagen." }
            finally { loading = false }
        }
    }
    AppPage("Deine Studio-Skripte", "VIDEOMAKER", "Öffne ein Skript aus deinem Studio auf diesem Gerät.", onBack = onBack, showRemote = false) {
        if (projects == null) {
            AppPanel {
                Text("Mit deinem Studio verbinden", style = MaterialTheme.typography.titleLarge)
                Text("Melde dich mit deinem Videomaker-Passwort an.", color = SoftText, style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("Studio-Passwort") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !loading)
                MainAction(if (loading) "Verbinde …" else "Anmelden", enabled = password.isNotEmpty() && !loading, onClick = {
                    val entered = password
                    password = ""
                    scope.launch {
                        loading = true; error = null
                        try { client.login(entered); projects = client.projects(); stale = false } catch (e: Exception) { error = e.message ?: "Verbindung fehlgeschlagen." }
                        finally { loading = false }
                    }
                })
            }
        } else {
            Text("Web-Fernsteuerung", style = MaterialTheme.typography.titleLarge)
            StatusPanel(remoteStatus, detail = if (remoteEnabled) "Dieses Gerät kann im Studio-Dashboard ausgewählt werden." else "Verbinde dieses Gerät mit dem Dashboard, um es fernzusteuern.", active = remoteEnabled && remoteStatus == "Verbunden")
            QuietAction(if (remoteEnabled) "Fernsteuerung beenden" else "Fernsteuerung aktivieren") { onRemote(!remoteEnabled) }
            Spacer(Modifier.height(4.dp))
            Text("Skripte", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            if (stale) Text("Zuletzt geladene Daten: Die Aktualisierung ist fehlgeschlagen. Hinweise können veraltet sein.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            if (projects!!.isEmpty()) Text("Im Studio sind noch keine Skripte gespeichert.", color = SoftText)
            projects!!.forEach { project ->
                val notice = if (stale || loading) project.generationNotice.copy(status = GenerationNoticeStatus.INCOMPLETE) else project.generationNotice
                ScriptTile(project.title, notice.summary()) { onSelect(project.copy(generationNotice = notice)) }
            }
            QuietAction(if (loading) "Lade …" else "Skripte aktualisieren", enabled = !loading) { load() }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        Text("Beim Öffnen wird eine lokale Kopie erstellt. Änderungen werden nicht ins Studio zurückgeschrieben.", color = SoftText, style = MaterialTheme.typography.bodySmall)
    }
}
