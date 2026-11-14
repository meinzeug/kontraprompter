package de.kontrakurs.kontraprompter.cloud

import de.kontrakurs.kontraprompter.video.RecordingOrigin
import de.kontrakurs.kontraprompter.video.LocalRecording
import de.kontrakurs.kontraprompter.video.SavedRecording
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class LiveCameraUploadPolicyTest {
    private val id = "8f991102-1260-4349-a59e-d65e80a80db0"
    private val project = "835c2eca-7783-4b90-9a23-ff20f43a53b8"
    private val entry = LiveBindingEntry(LiveChunkBinding(id, project, "a".repeat(64)), false)
    private fun recording(origin: RecordingOrigin, projectId: String? = null, partial: Boolean = false) = SavedRecording(id, File("original.mp4"), 100, 20, 1, "b".repeat(64), partial, "user", projectId, origin)
    @Test fun `stale manifest cannot assert a living recorder`() {
        assertNull(liveRecorderId(LiveRecorderSnapshot(id, false)))
        assertEquals(id, liveRecorderId(LiveRecorderSnapshot(id, true)))
        assertNull(liveRecorderId(LiveRecorderSnapshot(null, true)))
    }
    @Test fun `only living recording and pause states feed active upload`() {
        assertEquals(LiveRecorderSnapshot(id, true), recorderSnapshot(LocalRecording.Recording(id)))
        assertEquals(LiveRecorderSnapshot(id, true), recorderSnapshot(LocalRecording.Paused(id)))
        assertEquals(LiveRecorderSnapshot(null, false), recorderSnapshot(LocalRecording.Preparing(id)))
        assertEquals(LiveRecorderSnapshot(null, false), recorderSnapshot(LocalRecording.Finalizing(id)))
        assertEquals(LiveRecorderSnapshot(null, false), recorderSnapshot(LocalRecording.Idle))
    }
    @Test fun `only matching complete LAN origin can finalize`() {
        assertTrue(mayFinalizeLive(entry, recording(RecordingOrigin.LAN, project)))
        assertTrue(mayFinalizeLive(entry, recording(RecordingOrigin.LAN_UNASSIGNED)))
        assertFalse(mayFinalizeLive(entry, recording(RecordingOrigin.LAN, "f".repeat(36))))
        assertFalse(mayFinalizeLive(entry, recording(RecordingOrigin.LAN, project, true)))
        assertFalse(mayFinalizeLive(entry, recording(RecordingOrigin.MANUAL)))
        assertFalse(mayFinalizeLive(entry, recording(RecordingOrigin.WEB, project)))
        assertFalse(mayFinalizeLive(entry, recording(RecordingOrigin.LAN, project).copy(id = "other")))
    }
}
