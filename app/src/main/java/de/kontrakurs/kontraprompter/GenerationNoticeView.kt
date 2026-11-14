package de.kontrakurs.kontraprompter

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
private fun NoticeText(notice: GenerationNotice) {
    when (notice.status) {
        GenerationNoticeStatus.UNKNOWN -> Text("Für dieses Studio-Skript liegen keine Angaben zu Erstellhinweisen vor. Bitte Kanal und Text vor der Aufnahme prüfen.")
        GenerationNoticeStatus.INCOMPLETE -> Text("Die Erstellhinweise sind unvollständig, ungültig oder möglicherweise veraltet. Bitte das Skript im Studio prüfen.")
        GenerationNoticeStatus.PROVIDED -> Unit
    }
    notice.messages.forEach { Text(it) }
    Text("Hinweise beziehen sich auf die Erstellung. Nach Änderungen kann die lokale Kopie davon abweichen; sie sind keine Freigabe.", color = SoftText, style = MaterialTheme.typography.bodySmall)
}

@Composable
internal fun GenerationNoticePanel(notice: GenerationNotice) {
    if (!notice.needsAttention()) return
    Surface(color = Color(0xff302a20), shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, Color(0xffa88041)), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Hinweise zur Skripterstellung", style = MaterialTheme.typography.titleSmall, color = Color(0xffffd79b))
            NoticeText(notice)
        }
    }
}

/** Always visible during playback; full historical text remains accessible. */
@Composable
internal fun PrompterGenerationNotice(notice: GenerationNotice) {
    if (!notice.needsAttention()) return
    var details by remember(notice) { mutableStateOf(false) }
    Surface(color = Color(0xf029241c), shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, Color(0xffa88041)),
        modifier = Modifier.fillMaxWidth().clickable { details = true }) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("Hinweise zur Skripterstellung · Details öffnen", color = Color(0xffffd79b), style = MaterialTheme.typography.labelMedium)
            Text(notice.summary(), color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
        }
    }
    if (details) AlertDialog(
        onDismissRequest = { details = false },
        title = { Text("Hinweise zur Skripterstellung") },
        text = { Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) { NoticeText(notice) } },
        confirmButton = { TextButton(onClick = { details = false }) { Text("Schließen") } },
    )
}
