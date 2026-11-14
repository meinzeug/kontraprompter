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
    @Test fun `web owner cannot take over an active paired recording`() {
        assertTrue(canAcquireWebOwner(LocalRecording.Idle, false))
        assertFalse(canAcquireWebOwner(LocalRecording.Preparing("take"), false))
        assertFalse(canAcquireWebOwner(LocalRecording.Recording("take"), false))
        assertFalse(canAcquireWebOwner(LocalRecording.Paused("take"), false))
        assertFalse(canAcquireWebOwner(LocalRecording.Finalizing("take"), false))
        assertTrue(canAcquireWebOwner(LocalRecording.Recording("take"), true))
    }
}
