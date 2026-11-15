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
        assertEquals(setOf("roles", "scripts", "studio", "recordings", "help"), targets.toSet())
        assertFalse(targets.contains("connection"))
        assertFalse(targets.contains("upload"))
    }
    @Test fun remoteDisconnectRequiresNonPendingTerminalStatusWhileConnected() {
        for (phase in listOf("preparing", "recording", "paused", "finalizing", "failed", "unknown", "new-value"))
            assertFalse(phase, mayDisconnectRemote(ready = true, pending = false, phase = phase))
        for (phase in listOf("idle", "ready", "partial")) {
            assertTrue(phase, mayDisconnectRemote(ready = true, pending = false, phase = phase))
            assertFalse(phase, mayDisconnectRemote(ready = true, pending = true, phase = phase))
        }
        assertTrue(mayDisconnectRemote(ready = false, pending = false, phase = "unknown"))
    }
    @Test fun cameraCannotReplaceAnExistingSession() {
        assertFalse(mayStartCamera(sessionPresent = true))
        assertTrue(mayStartCamera(sessionPresent = false))
    }
    @Test fun editorAndConfigurationMarkScriptsAsCurrent() {
        for (page in listOf("scripts", "editor", "settings", "calibration")) assertTrue(DrawerDestination.SCRIPTS.isCurrent(page))
        assertFalse(DrawerDestination.SCRIPTS.isCurrent("roles"))
        assertTrue(DrawerDestination.START.isCurrent("roles"))
    }
}
