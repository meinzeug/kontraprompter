package de.kontrakurs.kontraprompter.video

import org.junit.Assert.*
import org.junit.Test

class RecordingOwnerPolicyTest {
    @Test fun `remote pause and resume require remote owner and exact id`() {
        assertTrue(recordingControlAllowed(true, true, "take-a", "take-a"))
        assertFalse(recordingControlAllowed(false, true, "take-a", "take-a"))
        assertFalse(recordingControlAllowed(true, true, "take-a", "take-b"))
        assertFalse(recordingControlAllowed(true, true, "take-a", null))
        assertFalse(recordingControlAllowed(true, false, "take-a", "take-a"))
        assertTrue(recordingControlAllowed(false, false, "take-a", null))
        assertFalse(recordingControlAllowed(false, false, "take-a", "take-b"))
    }
}
