package de.kontrakurs.kontraprompter.cloud

import de.kontrakurs.kontraprompter.video.SavedRecording
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ManualUploadContinuationTest {
    private val recording = SavedRecording("00000000-0000-4000-8000-000000000001", File("unused"), 1024, 1000, 1, "a".repeat(64), false, "user")
    private val intent = ManualUploadIntent(recording.id, "00000000-0000-4000-8000-000000000002", recording.bytes, recording.sha256, "00000000-0000-4000-8000-000000000003", "c".repeat(64), "pending")
    private fun decide(i: ManualUploadIntent = intent, r: SavedRecording = recording, login: Boolean = true, same: Boolean = true, project: Boolean = true, sameAuthorization: Boolean = true) =
        manualResumeDecision(i, r, login, same, project, sameAuthorization)

    @Test fun `continuation requires the original complete bytes and exact recording`() {
        assertEquals(ManualResumeDecision.START, decide())
        assertEquals(ManualResumeDecision.RECORDING_CHANGED, decide(r = recording.copy(bytes = 1025)))
        assertEquals(ManualResumeDecision.RECORDING_CHANGED, decide(r = recording.copy(sha256 = "b".repeat(64))))
        assertEquals(ManualResumeDecision.RECORDING_CHANGED, decide(r = recording.copy(id = "00000000-0000-4000-8000-000000000004")))
        assertEquals(ManualResumeDecision.RECORDING_CHANGED, decide(r = recording.copy(partial = true)))
        assertEquals(ManualResumeDecision.REMOTE_RECORDING, decide(r = recording.copy(projectId = intent.projectId)))
    }
    @Test fun `continuation requires the same live session and original accessible project`() {
        assertEquals(ManualResumeDecision.NO_SESSION, decide(login = false))
        assertEquals(ManualResumeDecision.SESSION_CHANGED, decide(same = false))
        assertEquals(ManualResumeDecision.SESSION_CHANGED, decide(sameAuthorization = false))
        assertEquals(ManualResumeDecision.PROJECT_MISSING, decide(project = false))
    }
    @Test fun `session binding is stable only for the same bearer and does not store it`() {
        val client = StudioClient()
        val first = StudioClient.ManualUploadSession("kontrakurs_session=first-secret")
        val again = StudioClient.ManualUploadSession("kontrakurs_session=first-secret")
        val other = StudioClient.ManualUploadSession("kontrakurs_session=other-secret")
        val binding = client.manualUploadSessionBinding(first)
        assertEquals(64, binding.length)
        assertEquals(binding, client.manualUploadSessionBinding(again))
        assertNotEquals(binding, client.manualUploadSessionBinding(other))
        assertFalse(binding.contains("first-secret"))
    }
    @Test fun `paused blocked and completed requests never start automatically`() {
        assertEquals(ManualResumeDecision.PAUSED, decide(i = intent.copy(state = "paused")))
        assertEquals(ManualResumeDecision.BLOCKED, decide(i = intent.copy(state = "blocked")))
        assertEquals(ManualResumeDecision.HISTORICAL_CONFIRMATION, decide(i = intent.copy(state = "complete")))
        assertEquals(ManualResumeDecision.RECORDING_CHANGED, decide(i = intent.copy(state = "unexpected")))
    }
    @Test fun `late receipt cannot overwrite a pause or a newer request`() {
        val paused = finishManualIntent(intent, intent.generation, "paused")!!
        assertNull(finishManualIntent(paused, intent.generation, "complete"))
        val replacement = intent.copy(generation = "00000000-0000-4000-8000-000000000004")
        assertNull(finishManualIntent(replacement, intent.generation, "complete"))
        assertEquals("complete", finishManualIntent(intent, intent.generation, "complete")!!.state)
    }
}
