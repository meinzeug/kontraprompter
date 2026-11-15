package de.kontrakurs.kontraprompter

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

internal object ShellIcons {
    private fun icon(name: String, draw: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit) = ImageVector.Builder(
        name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f,
    ).apply { path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, pathBuilder = draw) }.build()
    val Menu = icon("Menu") { moveTo(4f, 6f); lineTo(20f, 6f); moveTo(4f, 12f); lineTo(20f, 12f); moveTo(4f, 18f); lineTo(20f, 18f) }
    val Prompter = icon("Prompter") { moveTo(4f, 4f); lineTo(20f, 4f); lineTo(20f, 20f); lineTo(4f, 20f); close(); moveTo(8f, 9f); lineTo(16f, 9f); moveTo(8f, 13f); lineTo(16f, 13f); moveTo(8f, 17f); lineTo(13f, 17f) }
    val Camera = icon("Camera") { moveTo(3f, 7f); lineTo(15f, 7f); lineTo(15f, 17f); lineTo(3f, 17f); close(); moveTo(15f, 10f); lineTo(21f, 7f); lineTo(21f, 17f); lineTo(15f, 14f) }
    val Back = icon("Back") { moveTo(19f, 12f); lineTo(5f, 12f); moveTo(11f, 6f); lineTo(5f, 12f); lineTo(11f, 18f) }
}
