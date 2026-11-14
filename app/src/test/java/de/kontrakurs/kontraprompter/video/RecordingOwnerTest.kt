package de.kontrakurs.kontraprompter.video

import org.junit.Assert.*
import org.junit.Test

class RecordingOwnerTest {
    @Test fun `exclusive web camera cannot be started by local or LAN capture`() {
        assertTrue(recordingOwnerAllowed(true, RecordingOrigin.WEB))
        assertFalse(recordingOwnerAllowed(true, RecordingOrigin.MANUAL))
        assertFalse(recordingOwnerAllowed(true, RecordingOrigin.LAN))
        assertTrue(recordingOwnerAllowed(false, RecordingOrigin.LAN))
    }
}
