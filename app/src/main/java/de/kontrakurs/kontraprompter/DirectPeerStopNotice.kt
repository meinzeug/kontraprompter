package de.kontrakurs.kontraprompter

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import de.kontrakurs.kontraprompter.video.DirectPeerStop

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
