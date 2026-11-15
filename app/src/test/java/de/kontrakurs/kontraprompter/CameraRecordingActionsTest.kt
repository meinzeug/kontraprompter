package de.kontrakurs.kontraprompter

import de.kontrakurs.kontraprompter.video.LocalRecording
import org.junit.Assert.*
import java.io.File
import org.junit.Test

class CameraRecordingActionsTest {
    @Test fun startRequiresConnectedPreviewAndNoBlockedFailure() {
        for (connected in listOf(false, true)) for (preview in listOf(false, true)) {
            val state = cameraRecordingActions(LocalRecording.Idle, connected, preview)
            assertTrue(state.start)
            assertEquals(connected && preview, state.startEnabled)
            assertFalse(state.stop)
        }
        assertFalse(cameraRecordingActions(LocalRecording.Failed("unfertig", blocked = true), true, true).startEnabled)
        assertTrue(cameraRecordingActions(LocalRecording.Failed("fehler", blocked = false), true, true).startEnabled)
    }
    @Test fun preparingRecordingAndPausedExposeOnlyApplicableControls() {
        val preparing = cameraRecordingActions(LocalRecording.Preparing("take"), true, true)
        assertTrue(preparing.stop); assertFalse(preparing.start); assertFalse(preparing.pause); assertFalse(preparing.resume)
        val recording = cameraRecordingActions(LocalRecording.Recording("take"), false, false)
        assertTrue(recording.pause); assertTrue(recording.stop); assertFalse(recording.start); assertFalse(recording.resume)
        val paused = cameraRecordingActions(LocalRecording.Paused("take"), false, false)
        assertTrue(paused.resume); assertTrue(paused.stop); assertFalse(paused.start); assertFalse(paused.pause)
    }
    @Test fun storedAndPartialTakesCanStartANewRecordingOnlyWhenReady() {
        val saved = LocalRecording.Ready("take", File("unused"), 10, 10, "manual")
        val partial = LocalRecording.Partial("take", File("unused"), 10, 10, "interrupted")
        for (state in listOf(saved, partial)) {
            assertTrue(cameraRecordingActions(state, connected = true, videoReady = true).startEnabled)
            assertFalse(cameraRecordingActions(state, connected = false, videoReady = true).startEnabled)
            assertFalse(cameraRecordingActions(state, connected = true, videoReady = true).stop)
        }
    }
    @Test fun finalizingNeverOffersNewStartOrDuplicateStop() {
        val state = cameraRecordingActions(LocalRecording.Finalizing("take"), true, true)
        assertFalse(state.start); assertFalse(state.stop); assertFalse(state.pause); assertFalse(state.resume)
    }
}
