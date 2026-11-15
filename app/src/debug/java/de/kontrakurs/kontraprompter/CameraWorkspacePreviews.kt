package de.kontrakurs.kontraprompter

import android.content.res.Configuration
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.kontrakurs.kontraprompter.video.LocalRecording

// System-light preview still uses the actual currently dark app theme.
@Preview(name = "Kamera kompakt – offline", widthDp = 320, heightDp = 640, showSystemUi = true, uiMode = Configuration.UI_MODE_NIGHT_NO)
@Preview(name = "Kamera kompakt – 200 %", widthDp = 320, heightDp = 640, fontScale = 2f, showSystemUi = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Kamera breit", widthDp = 840, heightDp = 480, showSystemUi = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun CameraOfflinePreview() {
    KontraTheme {
        AppBackground {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CameraRecordingPanel(LocalRecording.Idle, connected = false, videoReady = false, permissionMessage = null, onStart = {}, onPause = {}, onResume = {}, onStop = {})
                CameraConnectionStatus("Nicht verbunden", error = "Bitte WLAN und Kameragerät prüfen.")
            }
        }
    }
}

@Preview(name = "Aufnahme läuft – Verbindung unterbrochen", widthDp = 360, heightDp = 700, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun CameraRecordingPreview() {
    KontraTheme {
        AppBackground {
            CameraRecordingPanel(LocalRecording.Recording("preview"), connected = false, videoReady = true, permissionMessage = null, onStart = {}, onPause = {}, onResume = {}, onStop = {})
        }
    }
}

@Preview(name = "Wi-Fi Direct Hilfe – 200 %", widthDp = 320, heightDp = 640, fontScale = 2f, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Wi-Fi Direct Hilfe – breit", widthDp = 840, heightDp = 480, uiMode = Configuration.UI_MODE_NIGHT_NO)
@Composable
private fun WifiDirectHelpPreview() {
    KontraTheme {
        AppBackground {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                WifiDirectGuide(expanded = true, onToggle = {})
            }
        }
    }
}
