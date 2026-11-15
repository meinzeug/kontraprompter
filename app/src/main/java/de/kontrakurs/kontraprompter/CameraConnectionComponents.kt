package de.kontrakurs.kontraprompter

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import de.kontrakurs.kontraprompter.video.DirectPeerStop

/** A status remains readable without relying on its color. */
@Composable
internal fun CameraConnectionStatus(status: String, error: String?, detail: String? = null) {
    val space = LocalKontraSpacing.current
    Surface(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.padding(space.medium), verticalArrangement = Arrangement.spacedBy(space.extraSmall)) {
            Text("VERBINDUNG", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(status, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
internal fun CameraConnectionCard(content: @Composable ColumnScope.() -> Unit) {
    val space = LocalKontraSpacing.current
    Surface(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(space.medium), verticalArrangement = Arrangement.spacedBy(space.small), content = content)
    }
}

@Composable
internal fun DirectPeerStopNotice(state: DirectPeerStop.State) {
    val textId = when (state) {
        DirectPeerStop.State.IDLE -> return
        DirectPeerStop.State.PENDING -> R.string.direct_peer_stop_pending
        DirectPeerStop.State.CLOSED -> R.string.direct_peer_stop_called
        DirectPeerStop.State.NO_PEER -> R.string.direct_peer_stop_no_peer
        DirectPeerStop.State.FAILED -> R.string.direct_peer_stop_failed
    }
    Text(stringResource(textId), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        color = if (state == DirectPeerStop.State.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium)
}
