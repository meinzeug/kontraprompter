package de.kontrakurs.kontraprompter

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val Ink = Color(0xff0c0e12)
val Panel = Color(0xff1a1e25)
val PanelRaised = Color(0xff242932)
val Accent = Color(0xffff6b6b)
val SoftText = Color(0xffb7bec8)
data class RemoteIndicator(val status: String, val onStop: () -> Unit)
val LocalRemoteIndicator = staticCompositionLocalOf<RemoteIndicator?> { null }
data class CameraIndicator(val status: String, val connected: Boolean, val onShow: () -> Unit)
val LocalCameraIndicator = staticCompositionLocalOf<CameraIndicator?> { null }
internal val LocalDrawerOpen = staticCompositionLocalOf<(() -> Unit)?> { null }
private val DarkColors = darkColorScheme(
    primary = Accent, onPrimary = Color(0xff24090b),
    primaryContainer = Color(0xff54292d), onPrimaryContainer = Color(0xffffdcda),
    secondary = Color(0xffd7c2c1), onSecondary = Color(0xff392b2b),
    surface = Panel, onSurface = Color(0xfff4f5f7),
    surfaceVariant = PanelRaised, onSurfaceVariant = SoftText,
    background = Ink, onBackground = Color(0xfff4f5f7),
    outline = Color(0xff59616d), outlineVariant = Color(0xff343b46),
    error = Color(0xffffa8a2), onError = Color(0xff4a1212),
)

@Composable
fun KontraTheme(content: @Composable () -> Unit) {
    val default = Typography()
    val type = default.copy(
        headlineLarge = default.headlineLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.8).sp, lineHeight = 42.sp),
        headlineMedium = default.headlineMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp, lineHeight = 36.sp),
        titleLarge = default.titleLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
        bodyLarge = default.bodyLarge.copy(lineHeight = 24.sp),
        bodyMedium = default.bodyMedium.copy(lineHeight = 21.sp),
        labelLarge = default.labelLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.1.sp),
    )
    MaterialTheme(colorScheme = DarkColors, typography = type, shapes = Shapes(
        extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp),
        medium = RoundedCornerShape(18.dp), large = RoundedCornerShape(24.dp), extraLarge = RoundedCornerShape(28.dp)
    ), content = content)
}

@Composable
fun AppBackground(content: @Composable BoxScope.() -> Unit) {
    Surface(Modifier.fillMaxSize(), color = Color.Transparent, contentColor = MaterialTheme.colorScheme.onBackground) {
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xff181b21), Ink, Ink))), content = content)
    }
}

@Composable
fun AppPage(title: String, eyebrow: String, description: String? = null, onBack: (() -> Unit)? = null, backLabel: String = "Zurück", showRemote: Boolean = true, showCamera: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    val remote = LocalRemoteIndicator.current
    val camera = LocalCameraIndicator.current
    val openDrawer = LocalDrawerOpen.current
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.fillMaxHeight().widthIn(max = 640.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 26.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (onBack != null || openDrawer != null) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (onBack != null) BackAction(onBack, label = backLabel)
                Spacer(Modifier.weight(1f))
                if (openDrawer != null) TextButton(onClick = openDrawer, modifier = Modifier.heightIn(min = 48.dp)) { Text("Menü") }
            }
            if (showRemote && remote != null) RemoteBanner(remote)
            if (showCamera && camera != null) CameraBanner(camera)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(7.dp).background(Accent, RoundedCornerShape(50)))
                Text(eyebrow, color = SoftText, style = MaterialTheme.typography.labelMedium, letterSpacing = 1.1.sp, fontWeight = FontWeight.Bold)
            }
            Text(title, style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.onBackground)
            if (description != null) Text(description, color = SoftText, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(2.dp))
            content()
            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
fun CameraBanner(camera: CameraIndicator) {
    Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = Panel, border = BorderStroke(1.dp, Color(0xff3a414d))) {
        Row(Modifier.padding(start = 14.dp, end = 8.dp, top = 3.dp, bottom = 3.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(7.dp).background(if (camera.connected) Color(0xff91d6b2) else Color(0xffffcd8a), RoundedCornerShape(50)))
            Text("Kamera: ${camera.status}", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
            TextButton(onClick = camera.onShow, modifier = Modifier.heightIn(min = 48.dp)) { Text("Anzeigen", color = Color(0xffffaaa6)) }
        }
    }
}

@Composable
fun RemoteBanner(remote: RemoteIndicator, modifier: Modifier = Modifier) {
    Surface(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = Color(0xff25302e), border = BorderStroke(1.dp, Color(0xff435b52))) {
        Row(Modifier.padding(start = 14.dp, end = 8.dp, top = 3.dp, bottom = 3.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(7.dp).background(if (remote.status == "Verbunden") Color(0xff91d6b2) else Color(0xffffcd8a), RoundedCornerShape(50)))
            Text("Studio: ${remote.status}", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = Color(0xffe1f1e9))
            TextButton(onClick = remote.onStop, modifier = Modifier.heightIn(min = 48.dp)) { Text("Beenden", color = Color(0xffcaebd9)) }
        }
    }
}

@Composable
fun ScriptTile(title: String, subtitle: String, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
        color = Panel, border = BorderStroke(1.dp, Color(0xff343a44))) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, color = SoftText, style = MaterialTheme.typography.bodySmall)
            }
            Text("›", color = SoftText, fontSize = 28.sp)
        }
    }
}

@Composable
fun AppPanel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier = modifier.fillMaxWidth(), color = Panel.copy(alpha = .94f), shape = RoundedCornerShape(22.dp), border = BorderStroke(1.dp, Color(0xff343a44))) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
fun MainAction(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = RoundedCornerShape(16.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun QuietAction(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp), shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.filledTonalButtonColors(containerColor = PanelRaised, contentColor = Color(0xfff2f3f5))) {
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun BackAction(onClick: () -> Unit, label: String = "Zurück") {
    TextButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 0.dp), modifier = Modifier.heightIn(min = 48.dp)) {
        Text("‹  $label", color = Color(0xffff9a97), style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun RoleTile(title: String, subtitle: String, camera: Boolean, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp),
        color = Panel.copy(alpha = .96f), border = BorderStroke(1.dp, Color(0xff3a414d))) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.size(56.dp).background(Color(0xff3c262b), RoundedCornerShape(18.dp)), contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(27.dp)) {
                    val c = Color(0xffff9b98); val w = size.width; val h = size.height; val stroke = 2.dp.toPx()
                    if (camera) {
                        drawRoundRect(c, Offset(w*.08f,h*.22f), Size(w*.64f,h*.56f), CornerRadius(w*.14f), style = Stroke(stroke))
                        drawLine(c, Offset(w*.72f,h*.37f), Offset(w*.94f,h*.24f), stroke, cap = StrokeCap.Round)
                        drawLine(c, Offset(w*.94f,h*.24f), Offset(w*.94f,h*.76f), stroke, cap = StrokeCap.Round)
                        drawLine(c, Offset(w*.94f,h*.76f), Offset(w*.72f,h*.63f), stroke, cap = StrokeCap.Round)
                    } else {
                        drawRoundRect(c, Offset(w*.05f,h*.1f), Size(w*.9f,h*.8f), CornerRadius(w*.15f), style = Stroke(stroke))
                        listOf(.34f,.5f,.66f).forEach { y -> drawLine(c, Offset(w*.25f,h*y), Offset(w*.75f,h*y), stroke, cap = StrokeCap.Round) }
                    }
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Text(subtitle, color = SoftText, style = MaterialTheme.typography.bodyMedium)
            }
            Text("›", color = Color(0xffaeb5c0), fontSize = 30.sp)
        }
    }
}

@Composable
fun StatusPanel(status: String, detail: String? = null, error: String? = null, active: Boolean = true) {
    AppPanel {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(8.dp).background(if (error != null) MaterialTheme.colorScheme.error else if (active) Color(0xff91d6b2) else SoftText, RoundedCornerShape(50)))
            Text(status, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        }
        detail?.let { Text(it, color = SoftText, style = MaterialTheme.typography.bodySmall) }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
    }
}
