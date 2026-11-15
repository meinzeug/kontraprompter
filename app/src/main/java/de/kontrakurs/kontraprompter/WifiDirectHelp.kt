package de.kontrakurs.kontraprompter

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

/** Information only: leaving the app can close an active camera session. */
@Composable
internal fun WifiDirectHelp() {
    var expanded by rememberSaveable { mutableStateOf(false) }
    WifiDirectGuide(expanded = expanded, onToggle = { expanded = !expanded })
}

@Composable
internal fun WifiDirectGuide(expanded: Boolean, onToggle: () -> Unit) {
    AppPanel {
        val expandedLabel = stringResource(if (expanded) R.string.wifi_direct_expanded else R.string.wifi_direct_collapsed)
        TextButton(onClick = onToggle, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { stateDescription = expandedLabel }) {
            Text(stringResource(R.string.wifi_direct_title))
        }
        if (expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.wifi_direct_safety), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                Text(stringResource(R.string.wifi_direct_step_one), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                Text(stringResource(R.string.wifi_direct_step_two), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                Text(stringResource(R.string.wifi_direct_step_three), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                Text(stringResource(R.string.wifi_direct_caveat), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
