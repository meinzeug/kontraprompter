package de.kontrakurs.kontraprompter

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

@Preview(name = "Arbeitsbereiche · kompakt", widthDp = 320, heightDp = 640, showBackground = true)
@Preview(name = "Arbeitsbereiche · große Schrift", widthDp = 320, heightDp = 640, fontScale = 2f, uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Preview(name = "Arbeitsbereiche · breit", widthDp = 840, heightDp = 480, showBackground = true)
@Composable
private fun WorkflowPreview() {
    KontraTheme {
        AppBackground {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                WorkflowHeading("Gespeicherte Videos", "Aufnahmen bleiben auf diesem Gerät.")
                WorkflowEmpty("Noch keine Aufnahmen auf diesem Gerät.")
                ScriptTile("Mein Skript", "1.024 Zeichen") {}
            }
        }
    }
}
