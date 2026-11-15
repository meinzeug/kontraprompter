package de.kontrakurs.kontraprompter

import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/** Stable light-on-dark surface for text layered over arbitrary camera pixels. */
@Composable
internal fun KontraVideoOverlay(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(modifier = modifier, color = Color.Black.copy(alpha = ThemePalette.VIDEO_OVERLAY_ALPHA), contentColor = Color.White, content = content)
}
