package de.kontrakurs.kontraprompter

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import de.kontrakurs.kontraprompter.cloud.StudioClient
import de.kontrakurs.kontraprompter.cloud.StudioProject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun StudioScreen(client: StudioClient, remoteEnabled: Boolean, remoteStatus: String, onRemote: (Boolean) -> Unit, onSelect: (StudioProject) -> Unit, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var projects by remember { mutableStateOf<List<StudioProject>?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var stale by remember { mutableStateOf(false) }
    var checkingSession by remember(client) { mutableStateOf(client.hasSession()) }
    fun load() {
        scope.launch {
            loading = true; error = null
            try { projects = client.projects(); stale = false } catch (e: Exception) { stale = projects != null; error = e.message ?: "Verbindung fehlgeschlagen." }
            finally { loading = false }
        }
    }
    LaunchedEffect(client) {
        if (checkingSession) {
            loading = true; error = null
            try { projects = client.projects(); stale = false }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "Verbindung fehlgeschlagen." }
            finally { loading = false; checkingSession = false }
        }
    }
    AppPage(stringResource(R.string.studio_title), stringResource(R.string.studio_eyebrow), stringResource(R.string.studio_description), onBack = onBack, showRemote = false) {
        if (checkingSession) {
            StudioSurface { StudioMessage(stringResource(R.string.studio_connection_check), announce = true) }
        } else if (projects == null) {
            StudioSurface {
                Text(stringResource(R.string.studio_sign_in_title), style = MaterialTheme.typography.titleLarge)
                if (client.hasSession()) QuietAction(stringResource(if (loading) R.string.studio_connecting else R.string.studio_reconnect), enabled = !loading) { load() }
                StudioMessage(stringResource(R.string.studio_sign_in_description))
                OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text(stringResource(R.string.studio_password)) }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !loading)
                MainAction(stringResource(if (loading) R.string.studio_connecting else R.string.studio_sign_in), enabled = password.isNotEmpty() && !loading, onClick = {
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
            Text(stringResource(R.string.studio_remote_title), style = MaterialTheme.typography.titleLarge)
            StatusPanel(remoteStatus, detail = if (remoteEnabled) "Dieses Gerät kann im Studio-Dashboard ausgewählt werden." else "Verbinde dieses Gerät mit dem Dashboard, um es fernzusteuern.", active = remoteEnabled && remoteStatus == "Verbunden")
            QuietAction(if (remoteEnabled) "Fernsteuerung beenden" else "Fernsteuerung aktivieren") { onRemote(!remoteEnabled) }
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.studio_scripts_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            if (stale) StudioMessage("Zuletzt geladene Daten: Die Aktualisierung ist fehlgeschlagen. Hinweise können veraltet sein.", error = true, announce = true)
            if (projects!!.isEmpty()) StudioSurface { StudioMessage(stringResource(R.string.studio_empty), announce = true) }
            projects!!.forEach { project ->
                val notice = if (stale || loading) project.generationNotice.copy(status = GenerationNoticeStatus.INCOMPLETE) else project.generationNotice
                StudioProjectTile(project.title, notice.summary()) { onSelect(project.copy(generationNotice = notice)) }
            }
            QuietAction(stringResource(if (loading) R.string.studio_loading else R.string.studio_refresh), enabled = !loading) { load() }
        }
        error?.let { StudioMessage(it, error = true, announce = true) }
        StudioMessage(stringResource(R.string.studio_copy_note))
    }
}
