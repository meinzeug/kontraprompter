package de.kontrakurs.kontraprompter

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Consistent stroke icons; labels supply accessibility, so icons are decorative. */
private fun drawerIcon(destination: DrawerDestination): ImageVector = ImageVector.Builder(
    name = destination.name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f,
).apply {
    path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
        when (destination) {
            DrawerDestination.START -> { moveTo(3f, 11f); lineTo(12f, 3.5f); lineTo(21f, 11f); moveTo(5f, 10f); lineTo(5f, 20f); lineTo(19f, 20f); lineTo(19f, 10f); moveTo(10f, 20f); lineTo(10f, 14f); lineTo(14f, 14f); lineTo(14f, 20f) }
            DrawerDestination.SCRIPTS -> { moveTo(5f, 3f); lineTo(19f, 3f); lineTo(19f, 21f); lineTo(5f, 21f); close(); moveTo(8f, 8f); lineTo(16f, 8f); moveTo(8f, 12f); lineTo(16f, 12f); moveTo(8f, 16f); lineTo(14f, 16f) }
            DrawerDestination.STUDIO -> { moveTo(3f, 5f); lineTo(21f, 5f); lineTo(21f, 19f); lineTo(3f, 19f); close(); moveTo(10f, 9f); lineTo(15f, 12f); lineTo(10f, 15f); close() }
            DrawerDestination.RECORDINGS -> { moveTo(3f, 6f); lineTo(14f, 6f); lineTo(14f, 18f); lineTo(3f, 18f); close(); moveTo(14f, 10f); lineTo(21f, 6f); lineTo(21f, 18f); lineTo(14f, 14f) }
            DrawerDestination.SETTINGS -> { moveTo(12f, 4f); lineTo(12f, 8f); moveTo(12f, 16f); lineTo(12f, 20f); moveTo(4f, 12f); lineTo(8f, 12f); moveTo(16f, 12f); lineTo(20f, 12f); moveTo(7f, 7f); lineTo(9.2f, 9.2f); moveTo(14.8f, 14.8f); lineTo(17f, 17f); moveTo(17f, 7f); lineTo(14.8f, 9.2f); moveTo(9.2f, 14.8f); lineTo(7f, 17f); moveTo(9f, 12f); curveTo(9f, 8f, 15f, 8f, 15f, 12f); curveTo(15f, 16f, 9f, 16f, 9f, 12f) }
            DrawerDestination.HELP -> { moveTo(9f, 9f); curveTo(9f, 5f, 16f, 5f, 16f, 9f); curveTo(16f, 11.5f, 12f, 12f, 12f, 15f); moveTo(12f, 19f); lineTo(12f, 19.2f) }
        }
    }
}.build()

@Composable
private fun DrawerItems(page: String, onSelect: (DrawerDestination) -> Unit) {
    Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 16.dp)) {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = MaterialTheme.shapes.large,
        ) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Icon(androidx.compose.runtime.remember { drawerIcon(DrawerDestination.RECORDINGS) }, contentDescription = null, modifier = Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f)) {
                    Text("KontraPrompter", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    Text("Dein Produktionsstudio", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                }
            }
        }
        DrawerDestination.entries.forEach { destination ->
            val group = when (destination) {
                DrawerDestination.START -> "ARBEITSBEREICH"
                DrawerDestination.RECORDINGS -> "PRODUKTION"
                DrawerDestination.SETTINGS -> "EINSTELLUNGEN & INFO"
                else -> null
            }
            if (group != null) Text(group, modifier = Modifier.padding(start = 28.dp, top = 12.dp, bottom = 4.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val icon = androidx.compose.runtime.remember(destination) { drawerIcon(destination) }
            NavigationDrawerItem(
                label = { Text(destination.label) },
                icon = { Icon(icon, contentDescription = null) },
                selected = destination.isCurrent(page),
                onClick = { onSelect(destination) },
                modifier = Modifier.padding(horizontal = 12.dp),
                colors = NavigationDrawerItemDefaults.colors(
                    selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        }
    }
}

@Composable
internal fun ModalDrawerNavigationView(state: DrawerState, page: String, onSelect: (DrawerDestination) -> Unit) {
    ModalDrawerSheet(drawerState = state, modifier = Modifier.widthIn(max = 320.dp), drawerContainerColor = MaterialTheme.colorScheme.surface) { DrawerItems(page, onSelect) }
}

@Composable
internal fun PermanentDrawerNavigationView(page: String, onSelect: (DrawerDestination) -> Unit) {
    PermanentDrawerSheet(Modifier.widthIn(max = 320.dp), drawerContainerColor = MaterialTheme.colorScheme.surface) { DrawerItems(page, onSelect) }
}
