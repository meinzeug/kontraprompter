package de.kontrakurs.kontraprompter.video

import org.junit.Assert.*
import org.junit.Test

class CameraSwitchStateTest {
    @Test fun `switch blocks capture wrong owners and stale camera state`() {
        assertTrue(cameraSwitchAllowed(true, false, false, false, false, false, false, true, true, null))
        assertFalse(cameraSwitchAllowed(true, false, false, false, false, true, false, true, true, null))
        assertFalse(cameraSwitchAllowed(true, false, false, false, false, false, true, true, true, null))
        assertFalse(cameraSwitchAllowed(true, false, false, true, false, false, false, true, true, null))
        assertTrue(cameraSwitchAllowed(true, false, false, true, true, false, false, true, true, true))
        assertFalse(cameraSwitchAllowed(true, false, false, true, true, false, false, true, true, false))
        assertFalse(cameraSwitchAllowed(true, false, false, true, true, false, false, true, true, null))
        assertFalse(cameraSwitchAllowed(true, false, false, false, true, false, false, true, true, true))
        assertFalse(cameraSwitchAllowed(true, false, false, false, false, false, false, false, true, null))
    }
}
