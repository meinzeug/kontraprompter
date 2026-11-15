package de.kontrakurs.kontraprompter

import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

@Composable
internal fun StudioSurface(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(LocalKontraSpacing.current.medium), verticalArrangement = Arrangement.spacedBy(LocalKontraSpacing.current.medium), content = content)
    }
}

internal fun studioAnnouncementModifier(announce: Boolean = false): Modifier =
    if (announce) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier

@Composable
internal fun StudioMessage(text: String, error: Boolean = false, announce: Boolean = false) {
    Text(
        text = text,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium,
        modifier = studioAnnouncementModifier(announce),
    )
}

@Composable
internal fun StudioProjectTile(title: String, detail: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(Modifier.padding(LocalKontraSpacing.current.medium), horizontalArrangement = Arrangement.spacedBy(LocalKontraSpacing.current.medium), verticalAlignment = Alignment.CenterVertically) {
            Icon(ShellIcons.Prompter, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(LocalKontraSpacing.current.extraSmall)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Preview(name = "Studio kompakt · 200 %", widthDp = 320, fontScale = 2f, uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Preview(name = "Studio breit", widthDp = 840, uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Composable
private fun StudioElementsPreview() {
    KontraTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                StudioSurface { Text("Videomaker", style = MaterialTheme.typography.titleLarge); StudioMessage("Deine lokalen Aufnahmen bleiben auf diesem Gerät.") }
                StudioProjectTile("Beispielskript", "Als Ziel auswählen", onClick = {})
                StudioMessage("Verbindung unterbrochen. Bitte erneut versuchen.", error = true)
            }
        }
    }
}
