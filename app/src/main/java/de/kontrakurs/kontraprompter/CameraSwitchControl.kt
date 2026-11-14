package de.kontrakurs.kontraprompter

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Accessible camera-and-arrow icon; disabled while recording or a switch is pending. */
@Composable
internal fun CameraSwitchControl(enabled: Boolean, onClick: () -> Unit) {
    val ink = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else .38f)
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(48.dp).semantics { contentDescription = "Kamera wechseln" }) {
        Canvas(Modifier.size(28.dp)) {
            val stroke = 1.8.dp.toPx()
            drawRoundRect(ink, topLeft = Offset(size.width * .12f, size.height * .28f), size = Size(size.width * .76f, size.height * .55f), cornerRadius = CornerRadius(size.width * .09f), style = Stroke(stroke))
            drawCircle(ink, radius = size.width * .16f, center = Offset(size.width * .5f, size.height * .55f), style = Stroke(stroke))
            drawArc(ink, startAngle = 210f, sweepAngle = 180f, useCenter = false, topLeft = Offset(size.width * .28f, size.height * .02f), size = Size(size.width * .48f, size.height * .48f), style = Stroke(stroke))
            val arrow = Path().apply { moveTo(size.width * .75f, size.height * .12f); lineTo(size.width * .77f, size.height * .27f); lineTo(size.width * .63f, size.height * .23f) }
            drawPath(arrow, ink, style = Stroke(stroke))
        }
    }
}
