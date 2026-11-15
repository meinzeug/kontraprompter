package de.kontrakurs.kontraprompter

import org.junit.Assert.*
import org.junit.Test

class DrawerNavigationTest {
    @Test fun guardedFlowsDoNotExposeDrawer() {
        for (page in listOf("connection", "prompter", "upload", "unknown")) assertFalse(page, canShowDrawer(page, false, false))
        for (page in listOf("roles", "scripts", "editor", "studio", "recordings", "settings", "calibration", "help")) {
            assertTrue(page, canShowDrawer(page, false, false))
            assertFalse(page, canShowDrawer(page, true, false))
            assertFalse(page, canShowDrawer(page, false, true))
        }
    }
    @Test fun onlyApprovedNonSessionActionsAreDrawerDestinations() {
        val targets = DrawerDestination.entries.map { it.route }
        assertEquals(targets.size, targets.toSet().size)
        assertEquals(setOf("roles", "scripts", "studio", "recordings", "settings", "help"), targets.toSet())
        assertFalse(targets.contains("connection"))
        assertFalse(targets.contains("upload"))
    }
    @Test fun backBelongsToDrawerWhileOpenOrOpening() {
        assertFalse(drawerInterceptsBack(currentOpen = false, targetOpen = false))
        assertTrue(drawerInterceptsBack(currentOpen = true, targetOpen = false))
        assertTrue(drawerInterceptsBack(currentOpen = false, targetOpen = true))
        assertTrue(drawerInterceptsBack(currentOpen = true, targetOpen = true))
    }
    @Test fun settingsAndHelpCannotCreateBackLoops() {
        assertEquals("editor", drawerReturnOrigin(DrawerDestination.HELP, "settings", "roles", "editor"))
        assertEquals("editor", drawerReturnOrigin(DrawerDestination.SETTINGS, "help", "editor", "roles"))
        assertEquals("roles", drawerReturnOrigin(DrawerDestination.SETTINGS, "help", "settings", "editor"))
        assertEquals("roles", drawerReturnOrigin(DrawerDestination.HELP, "settings", "roles", "help"))
        assertEquals("studio", drawerReturnOrigin(DrawerDestination.HELP, "studio", "roles", "editor"))
    }
    @Test fun remoteDisconnectRequiresNonPendingTerminalStatusWhileConnected() {
        for (phase in listOf("preparing", "recording", "paused", "finalizing", "failed", "unknown", "new-value"))
            assertFalse(phase, mayDisconnectRemote(ready = true, pending = false, phase = phase))
        for (phase in listOf("idle", "ready", "partial")) {
            assertTrue(phase, mayDisconnectRemote(ready = true, pending = false, phase = phase))
            assertFalse(phase, mayDisconnectRemote(ready = true, pending = true, phase = phase))
        }
        assertFalse(mayDisconnectRemote(ready = false, pending = false, phase = "unknown"))
    }
    @Test fun recoveryIsBoundToSessionGenerationAndBothSnapshots() {
        val session = Any()
        val captured = RecoverySnapshot(session, 3L, "offline", "unknown")
        assertTrue(captured.matches(session, 3L, "offline", "unknown"))
        assertFalse(captured.matches(Any(), 3L, "offline", "unknown"))
        assertFalse(captured.matches(session, 4L, "offline", "unknown"))
        assertFalse(captured.matches(session, 3L, "connected", "unknown"))
        assertFalse(captured.matches(session, 3L, "offline", "recording"))
    }
    @Test fun cameraCannotReplaceAnExistingSession() {
        assertFalse(mayStartCamera(sessionPresent = true))
        assertTrue(mayStartCamera(sessionPresent = false))
    }
    @Test fun editorAndConfigurationMarkScriptsAsCurrent() {
        for (page in listOf("scripts", "editor", "calibration")) assertTrue(DrawerDestination.SCRIPTS.isCurrent(page))
        assertFalse(DrawerDestination.SCRIPTS.isCurrent("roles"))
        assertTrue(DrawerDestination.START.isCurrent("roles"))
        assertTrue(DrawerDestination.SETTINGS.isCurrent("settings"))
    }
}
