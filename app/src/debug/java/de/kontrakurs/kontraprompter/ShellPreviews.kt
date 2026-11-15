package de.kontrakurs.kontraprompter

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.tooling.preview.Preview

@Preview(name = "Start kompakt", widthDp = 320, heightDp = 640, uiMode = Configuration.UI_MODE_NIGHT_NO, showSystemUi = true)
@Preview(name = "Start große Schrift", widthDp = 320, heightDp = 640, fontScale = 2f, uiMode = Configuration.UI_MODE_NIGHT_YES, showSystemUi = true)
@Preview(name = "Start breit", widthDp = 840, heightDp = 480, uiMode = Configuration.UI_MODE_NIGHT_YES, showSystemUi = true)
@Composable
private fun ShellPreview() {
    KontraTheme {
        CompositionLocalProvider(LocalShellVisible provides true, LocalDrawerOpen provides {}) {
            AppBackground {
                AppPage("Dein Studio", "KONTRAPROMPTER", "Wähle, wie du dieses Gerät beim Dreh einsetzen möchtest.") {
                    StudioRoleChoice("Teleprompter", "Skripte lesen und die Aufnahme steuern", camera = false, primary = true) {}
                    StudioRoleChoice("Kamera", "Video und Ton auf diesem Gerät aufnehmen", camera = true, primary = false) {}
                }
            }
        }
    }
}
