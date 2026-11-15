package de.kontrakurs.kontraprompter

import android.content.res.Configuration
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

// Preview exercises the shared page used by editor, pairing, studio and upload.
// System bars are illustrative; only a device can exercise cutouts and the real IME.
@Preview(name = "Kompakt – System hell", widthDp = 320, heightDp = 640, showSystemUi = true, uiMode = Configuration.UI_MODE_NIGHT_NO)
@Preview(name = "Kompakt – System dunkel, große Schrift", widthDp = 320, heightDp = 640, fontScale = 2f, showSystemUi = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Breit – System dunkel", widthDp = 840, heightDp = 480, showSystemUi = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun InsetsPagePreview() {
    // The current app theme remains dark in both system modes.
    KontraTheme {
        AppBackground {
            AppPage("Skript bearbeiten", "TELEPROMPTER", onBack = {}) {
                OutlinedTextField(value = "Skripttext", onValueChange = {}, label = { Text("Titel") })
                Spacer(Modifier.height(12.dp))
                MainAction("Teleprompter öffnen") {}
            }
        }
    }
}
