package de.kontrakurs.kontraprompter.video

import org.junit.Assert.*
import org.junit.Test

class CameraSwitchStateTest {
    @Test fun `switch blocks capture and wrong owners`() {
        assertTrue(cameraSwitchAllowed(true, false, false, false, false, false, false, true))
        assertFalse(cameraSwitchAllowed(true, false, false, false, false, true, false, true))
        assertFalse(cameraSwitchAllowed(true, false, false, false, false, false, true, true))
        assertFalse(cameraSwitchAllowed(true, false, false, true, false, false, false, true))
        assertTrue(cameraSwitchAllowed(true, false, false, true, true, false, false, true))
        assertFalse(cameraSwitchAllowed(true, false, false, false, true, false, false, true))
        assertFalse(cameraSwitchAllowed(true, false, false, false, false, false, false, false))
    }
}
