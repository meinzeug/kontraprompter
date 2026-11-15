package de.kontrakurs.kontraprompter

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

@Composable
private fun ThemeExample() {
    val space = LocalKontraSpacing.current
    Surface(color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) {
        Column(Modifier.padding(space.large), verticalArrangement = Arrangement.spacedBy(space.medium)) {
            Text("KontraPrompter", style = MaterialTheme.typography.headlineMedium)
            Text("Kamera und Teleprompter", style = MaterialTheme.typography.bodyLarge)
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurfaceVariant) {
                Text("Bereit für die Aufnahme", Modifier.padding(space.medium))
            }
            Button(onClick = {}) { Text("Weiter") }
            Box(Modifier.fillMaxWidth().height(72.dp).background(Brush.horizontalGradient(listOf(Color.Black, Color.White)))) {
                KontraVideoOverlay(Modifier.padding(space.small)) {
                    Text("Kamerastatus", Modifier.padding(space.small), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@Preview(name = "Hell · Fallback", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_NO)
@Composable private fun LightThemePreview() { KontraAdaptiveTheme(darkTheme = false, dynamicColor = false) { ThemeExample() } }

@Preview(name = "Dunkel · Fallback", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable private fun DarkThemePreview() { KontraAdaptiveTheme(darkTheme = true, dynamicColor = false) { ThemeExample() } }

@Preview(name = "Große Schrift · 200 %", showBackground = true, widthDp = 320, fontScale = 2f)
@Composable private fun LargeTextThemePreview() { KontraAdaptiveTheme(darkTheme = false, dynamicColor = false) { ThemeExample() } }

@Preview(name = "Kompakt · 320 dp", showBackground = true, widthDp = 320)
@Composable private fun CompactThemePreview() { KontraAdaptiveTheme(darkTheme = false, dynamicColor = false) { ThemeExample() } }

@Preview(name = "Breit · 840 dp", showBackground = true, widthDp = 840)
@Composable private fun ExpandedThemePreview() { KontraAdaptiveTheme(darkTheme = true, dynamicColor = false) { ThemeExample() } }
