package de.kontrakurs.kontraprompter

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

@Preview(name = "Bedienleiste · helles Video", widthDp = 320, heightDp = 240, showBackground = true)
@Preview(name = "Bedienleiste · große Schrift", widthDp = 320, heightDp = 320, fontScale = 2f, uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Preview(name = "Bedienleiste · quer", widthDp = 840, heightDp = 240, showBackground = true)
@Composable
private fun PrompterOverlayPreview() {
    KontraTheme {
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color.Black, Color.White))).padding(12.dp), contentAlignment = Alignment.BottomCenter) {
            PrompterOverlay(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = {}, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Editor") }
                    TextButton(onClick = {}, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Anfang") }
                    Button(onClick = {}, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Start") }
                }
            }
        }
    }
}
