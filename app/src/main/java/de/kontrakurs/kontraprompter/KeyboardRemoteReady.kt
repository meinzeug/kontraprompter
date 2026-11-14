package de.kontrakurs.kontraprompter

import android.content.res.ColorStateList
import android.graphics.Color as AndroidColor
import android.widget.Button
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/** Explicit user action: this real, visible button owns its own Android input focus. */
@Composable
internal fun KeyboardRemoteReady(activity: MainActivity, descriptor: String, connected: Boolean) {
    DisposableEffect(descriptor) { onDispose { activity.disarmKeyboardRemote() } }
    val currentDescriptor by rememberUpdatedState(descriptor)
    val ready = activity.shutterKeyboardReady && activity.keyboardRemoteReadyFor(descriptor)
    val label = when {
        !connected -> "Ausgewählte Fernbedienung nicht verbunden"
        ready -> "Enter-Auslöser bereit – Fokus behalten"
        else -> "Enter-Auslöser nicht bereit – aktivieren"
    }
    AndroidView(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        factory = { context ->
            Button(context).apply {
                isFocusableInTouchMode = true
                setTextColor(AndroidColor.WHITE)
                backgroundTintList = ColorStateList.valueOf(AndroidColor.rgb(59, 65, 74))
                setOnFocusChangeListener { view, focused -> if (!focused) activity.disarmKeyboardRemote(view) }
                setOnClickListener { view -> activity.armKeyboardRemote(view, currentDescriptor) }
            }
        },
        update = { view ->
            view.isEnabled = connected
            view.text = label
            view.contentDescription = label
        },
    )
}
