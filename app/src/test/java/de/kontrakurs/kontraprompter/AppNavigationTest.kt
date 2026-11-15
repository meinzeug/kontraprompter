package de.kontrakurs.kontraprompter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppNavigationTest {
    @Test fun `menu is available on normal pages but not during sensitive flows`() {
        for (page in listOf("roles", "scripts", "editor", "studio", "recordings", "settings", "settings_text", "help"))
            assertTrue(page, showAppMenu(page, cameraHost = false))
        for (page in listOf("connection", "prompter", "upload"))
            assertFalse(page, showAppMenu(page, cameraHost = false))
        for (page in listOf("roles", "connection", "settings"))
            assertFalse(page, showAppMenu(page, cameraHost = true))
    }

    @Test fun `setting details go to index and index returns to its origin`() {
        for (page in listOf("settings_text", "settings_video", "settings_remote", "settings_display"))
            assertEquals("settings", settingsBackPage(page, "editor"))
        assertEquals("editor", settingsBackPage("settings", "editor"))
        assertEquals("roles", settingsBackPage("settings", "roles"))
    }
}
