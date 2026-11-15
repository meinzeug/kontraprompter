package de.kontrakurs.kontraprompter.video

import org.junit.Assert.*
import org.junit.Test

class PairedCapturePolicyTest {
    @Test fun `valid camera status clears only the stale disconnected notice`() {
        assertNull(pairedMessageAfterStatus(PAIRED_DISCONNECTED_MESSAGE, null))
        assertEquals("Kamera meldet Fehler", pairedMessageAfterStatus(PAIRED_DISCONNECTED_MESSAGE, "Kamera meldet Fehler"))
        assertEquals("Befehl nicht bestätigt", pairedMessageAfterStatus("Befehl nicht bestätigt", null))
        assertNull(pairedMessageAfterStatus(null, null))
    }

    @Test fun `shutter command remains tied to its phase take and approved connection`() {
        assertTrue(shutterRequestMatches("recording", "take-1", "recording", "take-1", false, true))
        assertFalse(shutterRequestMatches("recording", "take-1", "recording", "take-2", false, true))
        assertFalse(shutterRequestMatches("recording", "take-1", "paused", "take-1", false, true))
        assertFalse(shutterRequestMatches("recording", "take-1", "recording", "take-1", true, true))
        assertFalse(shutterRequestMatches("recording", "take-1", "recording", "take-1", false, false))
        assertTrue(shutterRequestMatches("idle", null, "idle", null, false, true))
        assertFalse(shutterRequestMatches("idle", null, "recording", "take-1", false, true))
    }
    @Test fun `only legal current phase admits each action`() {
        assertTrue(pairedActionAllowed("start", "idle", false))
        assertTrue(pairedActionAllowed("pause", "recording", false))
        assertTrue(pairedActionAllowed("resume", "paused", false))
        assertTrue(pairedActionAllowed("stop", "paused", false))
        assertFalse(pairedActionAllowed("start", "recording", false))
        assertFalse(pairedActionAllowed("pause", "paused", false))
        assertFalse(pairedActionAllowed("resume", "recording", false))
        assertFalse(pairedActionAllowed("stop", "unknown", false))
        assertFalse(pairedActionAllowed("stop", "recording", true))
        assertFalse(pairedActionAllowed("delete", "idle", false))
        assertTrue(pairedActionAllowed("switch_camera", "idle", false))
        assertFalse(pairedActionAllowed("switch_camera", "recording", false))
        assertFalse(pairedActionAllowed("switch_camera", "paused", false))
        assertFalse(pairedActionAllowed("switch_camera", "finalizing", false))
    }
    @Test fun `a repeated command never executes again or accepts changed payload`() {
        val ledger = PairedCommandLedger(); var count = 0
        val command = PairedCommand("start", "recording-1", "project-1")
        assertTrue(ledger.execute("request", command) { count++; true })
        assertTrue(ledger.execute("request", command) { count++; false })
        assertFalse(ledger.execute("request", command.copy(projectId = "other")) { count++; true })
        assertFalse(ledger.execute("request", command.copy(expectedFront = true)) { count++; true })
        assertEquals(1, count)
    }
    @Test fun `rejection is sticky and no alternate action uses its identifier`() {
        val ledger = PairedCommandLedger(); var count = 0
        val command = PairedCommand("pause", "recording-1", null)
        assertFalse(ledger.execute("request", command) { count++; false })
        assertFalse(ledger.execute("request", command) { count++; true })
        assertFalse(ledger.execute("request", command.copy(action = "stop")) { count++; true })
        assertEquals(1, count)
    }
    @Test fun `late acknowledgements and statuses cannot supersede current capture`() {
        assertFalse(isCurrentCaptureAck("new-request", "old-request"))
        assertFalse(isCurrentCaptureAck(null, "old-request"))
        assertTrue(isCurrentCaptureAck("new-request", "new-request"))
        assertFalse(isNewCaptureStatus(41, 42))
        assertFalse(isNewCaptureStatus(42, 42))
        assertTrue(isNewCaptureStatus(43, 42))
        assertFalse(isNewCaptureStatus(-1, -2))
    }
    @Test fun `late switch acknowledgement requires a correlated snapshot without replacing status`() {
        assertTrue(cameraAckNeedsStatus("switch_camera", true))
        assertFalse(cameraAckNeedsStatus("switch_camera", false))
        assertFalse(cameraAckNeedsStatus("pause", true))
        assertFalse(cameraStatusAnswersPending("switch_camera", "new", "old"))
        assertFalse(cameraStatusAnswersPending("pause", "new", "new"))
        assertFalse(cameraStatusAnswersPending("switch_camera", null, null))
        assertTrue(cameraStatusAnswersPending("switch_camera", "new", "new"))
    }

    @Test fun `start acknowledgement keeps disconnect pending until fresh correlated status`() {
        assertTrue(cameraAckNeedsStatus("start", true))
        assertTrue(cameraAckNeedsStatus("start", false))
        assertFalse(cameraStatusAnswersPending("start", "new", null))
        assertFalse(cameraStatusAnswersPending("start", "new", "old"))
        assertTrue(cameraStatusAnswersPending("start", "new", "new"))
        assertFalse(isNewCaptureStatus(7, 7))
        assertTrue(isNewCaptureStatus(8, 7))
    }

    @Test fun `start timeout cannot release a queued start before acknowledgement`() {
        assertTrue(retainPendingAfterTimeout("start"))
        assertFalse(retainPendingAfterTimeout("pause"))
        assertFalse(retainPendingAfterTimeout("switch_camera"))
        assertFalse(retainPendingAfterTimeout(null))
    }

    @Test fun `confirmed start clears only its own unresolved-status messages`() {
        assertNull(messageAfterConfirmedStartStatus(START_STATUS_TIMEOUT_MESSAGE))
        assertNull(messageAfterConfirmedStartStatus(START_STATUS_STALE_MESSAGE))
        assertNull(messageAfterConfirmedStartStatus(START_STATUS_SEND_FAILED_MESSAGE))
        assertEquals("Kamera meldet Fehler", messageAfterConfirmedStartStatus("Kamera meldet Fehler"))
        assertEquals("Befehl wurde von der Kamera nicht angenommen.", messageAfterConfirmedStartStatus("Befehl wurde von der Kamera nicht angenommen."))
    }
}
