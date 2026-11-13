package de.kontrakurs.kontraprompter

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import de.kontrakurs.kontraprompter.cloud.StudioClient
import de.kontrakurs.kontraprompter.cloud.StudioProject
import kotlinx.coroutines.launch

@Composable
fun StudioScreen(onSelect: (StudioProject) -> Unit, onBack: () -> Unit) {
    val client = remember { StudioClient() }
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var projects by remember { mutableStateOf<List<StudioProject>?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun load() {
        scope.launch {
            loading = true; error = null
            try { projects = client.projects() } catch (e: Exception) { error = e.message ?: "Verbindung fehlgeschlagen." }
            finally { loading = false }
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("KONTRAKURS STUDIO", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        Text("Deine Skripte", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Text("Skripte aus dem Videomaker öffnen und auf diesem Gerät im Teleprompter verwenden.", color = Color.LightGray)
        if (projects == null) {
            OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("Studio-Passwort") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !loading)
            Button(onClick = {
                val entered = password
                password = ""
                scope.launch {
                    loading = true; error = null
                    try { client.login(entered); projects = client.projects() } catch (e: Exception) { error = e.message ?: "Verbindung fehlgeschlagen." }
                    finally { loading = false }
                }
            }, enabled = password.isNotEmpty() && !loading, modifier = Modifier.fillMaxWidth()) { Text(if (loading) "VERBINDE …" else "ANMELDEN") }
        } else {
            if (projects!!.isEmpty()) Text("Im Studio sind noch keine Skripte gespeichert.")
            projects!!.forEach { project ->
                Card(Modifier.fillMaxWidth().clickable { onSelect(project) }) {
                    Column(Modifier.padding(20.dp)) {
                        Text(project.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Text("Im Teleprompter öffnen", color = Color.LightGray)
                    }
                }
            }
            OutlinedButton(onClick = { load() }, enabled = !loading, modifier = Modifier.fillMaxWidth()) { Text(if (loading) "LADE …" else "AKTUALISIEREN") }
        }
        error?.let { Text(it, color = Color(0xffff8888)) }
        Text("Beim Öffnen wird eine lokale Kopie erstellt. Änderungen werden nicht ins Studio zurückgeschrieben.", color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("ZURÜCK") }
    }
}
