package de.kontrakurs.kontraprompter

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.rememberDrawerState
import androidx.compose.ui.tooling.preview.Preview

// Current b9b2e76 theme is dark in both system modes; the follow-up theme
// integration must recheck these same previews for a true light palette.
@Preview(name = "Drawer kompakt – System hell", widthDp = 320, heightDp = 640, uiMode = Configuration.UI_MODE_NIGHT_NO)
@Preview(name = "Drawer kompakt – dunkel, 200 % Schrift", widthDp = 320, heightDp = 640, fontScale = 2f, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Drawer erweitert – dunkel", widthDp = 840, heightDp = 480, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun DrawerNavigationPreview() {
    KontraTheme { ModalDrawerNavigationView(state = rememberDrawerState(DrawerValue.Open), page = "editor", onBack = {}, onSelect = {}) }
}

@Preview(name = "Breites Fenster – breiter Drawer", widthDp = 840, heightDp = 480, uiMode = Configuration.UI_MODE_NIGHT_NO)
@Composable
private fun ExpandedDrawerNavigationPreview() {
    KontraTheme { PermanentDrawerNavigationView(page = "studio", onSelect = {}) }
}
