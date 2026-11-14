package de.kontrakurs.kontraprompter.cloud

import de.kontrakurs.kontraprompter.video.RecordingOrigin
import de.kontrakurs.kontraprompter.video.SavedRecording
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class LanAutoUploadDecisionTest {
    private val id = "00000000-0000-4000-8000-000000000001"
    private val project = "00000000-0000-4000-8000-000000000002"
    private val other = "00000000-0000-4000-8000-000000000003"
    private val binding = "b".repeat(64)
    private val original = SavedRecording(id, File("unused"), 1024, 1000, 1, "a".repeat(64), false, "user", project, RecordingOrigin.LAN)
    private val intent = LanUploadIntent(id, project, 1024, "a".repeat(64), binding)

    @Test fun onlyFinalLanRecordingsWithTheirOwnProjectCanBeSelected() {
        assertEquals(LanUploadDecision.READY, lanUploadDecision(original, null, binding, true, null))
        assertEquals(LanUploadDecision.CHANGED, lanUploadDecision(original.copy(origin = RecordingOrigin.WEB), null, binding, true, null))
        assertEquals(LanUploadDecision.CHANGED, lanUploadDecision(original.copy(projectId = null), null, binding, true, null))
        assertEquals(LanUploadDecision.CHANGED, lanUploadDecision(original.copy(partial = true), null, binding, true, null))
    }

    @Test fun networkRetryIsBoundToExactFileProjectAndLogin() {
        assertEquals(LanUploadDecision.READY, lanUploadDecision(original, intent, binding, true, "uncertain"))
        assertEquals(LanUploadDecision.NEED_LOGIN, lanUploadDecision(original, intent, null, true, "uncertain"))
        assertEquals(LanUploadDecision.SESSION_CHANGED, lanUploadDecision(original, intent, "c".repeat(64), true, "uncertain"))
        assertEquals(LanUploadDecision.PROJECT_MISSING, lanUploadDecision(original, intent, binding, false, "uncertain"))
        assertEquals(LanUploadDecision.CHANGED, lanUploadDecision(original.copy(projectId = other), intent, binding, true, "uncertain"))
        assertEquals(LanUploadDecision.CHANGED, lanUploadDecision(original.copy(bytes = 1025), intent, binding, true, "uncertain"))
        assertEquals(LanUploadDecision.CHANGED, lanUploadDecision(original.copy(sha256 = "c".repeat(64)), intent, binding, true, "uncertain"))
    }

    @Test fun unassignedLanRecordingRequiresIdempotentlyResolvedProject() {
        val unassigned = original.copy(projectId = null, origin = RecordingOrigin.LAN_UNASSIGNED)
        assertEquals(LanUploadDecision.CHANGED, lanUploadDecision(unassigned, null, binding, true, null))
        assertEquals(LanUploadDecision.READY, lanUploadDecision(unassigned, null, binding, true, null, project))
        assertEquals(LanUploadDecision.READY, lanUploadDecision(unassigned, intent, binding, true, "uncertain"))
        assertEquals(LanUploadDecision.CHANGED, lanUploadDecision(unassigned, intent, binding, true, "uncertain", other))
        assertEquals(LanUploadDecision.CHANGED, lanUploadDecision(unassigned.copy(projectId = project), intent, binding, true, "uncertain"))
        assertEquals(LanUploadDecision.SESSION_CHANGED, lanUploadDecision(unassigned, intent, "c".repeat(64), true, "uncertain"))
        assertEquals(LanUploadDecision.COMPLETE, lanUploadDecision(unassigned, intent, binding, true, "confirmed"))
    }

    @Test fun confirmedDoesNotGetReuploadedAndConflictsDoNotAutoRetry() {
        assertEquals(LanUploadDecision.COMPLETE, lanUploadDecision(original, intent, null, false, "confirmed"))
        assertEquals(LanUploadDecision.BLOCKED, lanUploadDecision(original, intent, binding, true, "conflict"))
        assertEquals(LanUploadDecision.BLOCKED, lanUploadDecision(original, intent, binding, true, "project_missing"))
        assertEquals("conflict", failedUploadPhase("file_changed"))
        assertEquals("uncertain", failedUploadPhase("storage"))
        assertEquals("uncertain", failedUploadPhase("auth"))
    }
}
