package de.kontrakurs.kontraprompter

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import de.kontrakurs.kontraprompter.video.LocalRecording
import de.kontrakurs.kontraprompter.video.recordingStopDescription

/** Action visibility exactly follows the recorder state; no state transition occurs here. */
internal data class CameraRecordingActions(val start: Boolean, val startEnabled: Boolean, val pause: Boolean, val resume: Boolean, val stop: Boolean)
internal fun cameraRecordingActions(recording: LocalRecording, connected: Boolean, videoReady: Boolean): CameraRecordingActions {
    val activeControls = recording is LocalRecording.Preparing || recording is LocalRecording.Recording || recording is LocalRecording.Paused
    val start = !activeControls && recording !is LocalRecording.Finalizing
    return CameraRecordingActions(
        start = start,
        startEnabled = start && connected && videoReady && (recording as? LocalRecording.Failed)?.blocked != true,
        pause = recording is LocalRecording.Recording,
        resume = recording is LocalRecording.Paused,
        stop = activeControls,
    )
}

@Composable
internal fun CameraRecordingPanel(
    recording: LocalRecording,
    connected: Boolean,
    videoReady: Boolean,
    permissionMessage: String?,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
) {
    val space = LocalKontraSpacing.current
    val actions = cameraRecordingActions(recording, connected, videoReady)
    val status = when (recording) {
        LocalRecording.Idle -> "Video und Ton lokal aufnehmen"
        is LocalRecording.Preparing -> "Aufnahme wird vorbereitet …"
        is LocalRecording.Recording -> if (connected) "Aufnahme läuft · Video und Ton" else "Aufnahme läuft ohne Videoverbindung weiter."
        is LocalRecording.Paused -> "Aufnahme pausiert · Video und Ton werden nicht aufgezeichnet."
        is LocalRecording.Finalizing -> "Video wird sicher gespeichert …"
        is LocalRecording.Ready -> "Aufnahme lokal gespeichert · ${recording.durationUs / 1_000_000} Sek. Unter ‚Aufnahmen‘ kannst du sie exportieren."
        is LocalRecording.Partial -> "Aufnahme unvollständig: ${recording.message} Ein geprüftes Teilvideo ist unter ‚Aufnahmen‘ exportierbar."
        is LocalRecording.Failed -> "Aufnahme fehlgeschlagen: ${recording.message}"
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(Modifier.padding(space.medium), verticalArrangement = Arrangement.spacedBy(space.small)) {
            Text("AUFNAHME", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                status,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.titleMedium,
                color = if (recording is LocalRecording.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
            (recording as? LocalRecording.Ready)?.let { current -> recordingStopDescription(current.reason)?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) } }
            if (actions.start) {
                if (!actions.startEnabled) Text(
                    when {
                        (recording as? LocalRecording.Failed)?.blocked == true -> "Die vorherige Aufnahme wird noch abgeschlossen."
                        !connected -> "Verbinde zuerst Kamera und Teleprompter."
                        else -> "Warte, bis die Kameravorschau bereit ist."
                    }, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
                )
                Button(onClick = onStart, enabled = actions.startEnabled, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                    Icon(ShellIcons.Camera, contentDescription = null)
                    Spacer(Modifier.width(space.small))
                    Text("Aufnahme starten")
                }
            }
            if (actions.pause) OutlinedButton(onClick = onPause, modifier = Modifier.fillMaxWidth().heightIn(min = space.touchTarget)) { Text("Aufnahme pausieren") }
            if (actions.resume) OutlinedButton(onClick = onResume, modifier = Modifier.fillMaxWidth().heightIn(min = space.touchTarget)) { Text("Aufnahme fortsetzen") }
            if (actions.stop) Button(onClick = onStop, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Aufnahme stoppen") }
            permissionMessage?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            Text("Beim Verlassen der App wird die Aufnahme beendet. Abgeschlossene Videos bleiben lokal.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
    }
}
