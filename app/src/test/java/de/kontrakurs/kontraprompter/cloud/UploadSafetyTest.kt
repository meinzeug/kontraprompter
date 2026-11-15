package de.kontrakurs.kontraprompter.cloud

import org.junit.Assert.*
import org.junit.Test

class UploadSafetyTest {
    private val entry = UploadEntry("recording", "project", "upload", 123, "a".repeat(64), "uploading")
    @Test fun `camera manifest pending is retryable but mismatches remain conflicts`() {
        assertEquals("manifest_pending", cameraUploadConflictKind("camera_manifest_pending", true))
        assertEquals("manifest_mismatch", cameraUploadConflictKind("camera_manifest_mismatch", true))
        assertEquals("manifest_partial", cameraUploadConflictKind("camera_manifest_partial", true))
        assertEquals("busy", cameraUploadConflictKind("camera_upload_busy", true))
        assertEquals("conflict", cameraUploadConflictKind("camera_manifest_pending", false))
        assertEquals("conflict", cameraUploadConflictKind("unknown", true))
    }
    @Test fun `late unauthorized response cannot clear a newer session`() {
        val cookie = SessionCookie()
        cookie.complete(cookie.beginLogin(), "old")
        val oldRequest = cookie.get()!!
        cookie.complete(cookie.beginLogin(), "new")
        cookie.invalidate(oldRequest)
        assertEquals("new", cookie.get()?.value)
        cookie.invalidate(cookie.get()!!)
        assertNull(cookie.get())
    }
    @Test fun `delayed journal result never confirms another recording or destination`() {
        val a = JournalLookup("recording-a", "project-a", "confirmed", false)
        assertNotNull(matchingJournal(a, "recording-a", "project-a"))
        assertNull(matchingJournal(a, "recording-a", "project-b"))
        assertNull(matchingJournal(a, "recording-b", "project-a"))
        val b = JournalLookup("recording-a", "project-b", null, false)
        assertNull(matchingJournal(b, "recording-a", "project-a"))
    }
    @Test fun `upload identity survives journal loss and binds recording to destination`() {
        val recording = "6f6f5827-8b97-45d3-90f2-65bf20dc22c0"
        val project = "7699f1c1-0bbc-4921-84b9-192135bb496c"
        val other = "a4b3cdea-d42b-4edf-8ab3-ea623d2dd8cc"
        assertEquals(uploadIdFor(recording, project), uploadIdFor(recording.uppercase(), project.uppercase()))
        assertNotEquals(uploadIdFor(recording, project), uploadIdFor(recording, other))
        assertNotEquals(uploadIdFor(recording, project), uploadIdFor(other, project))
        assertEquals("d20a348a-53dc-8f68-bc92-0e5d00768144", uploadIdFor("00000000-0000-4000-8000-000000000004", "00000000-0000-4000-8000-000000000003"))
    }
    @Test fun `receipt must match exact upload project size and digest`() {
        val receipt = UploadReceipt("upload", "project", 123, "a".repeat(64))
        assertTrue(receiptMatches(entry, receipt))
        assertFalse(receiptMatches(entry, receipt.copy(id = "other")))
        assertFalse(receiptMatches(entry, receipt.copy(projectId = "other")))
        assertFalse(receiptMatches(entry, receipt.copy(sizeBytes = 124)))
        assertFalse(receiptMatches(entry, receipt.copy(sha256 = "b".repeat(64))))
    }
    @Test fun `confirmation belongs to both recording and project`() {
        val confirmed = CloudUploadState.Confirmed("one", "project")
        assertTrue(confirmedFor(confirmed, "one", "project"))
        assertFalse(confirmedFor(confirmed, "two", "project"))
        assertFalse(confirmedFor(confirmed, "one", "other"))
    }
    @Test fun `continuation must not show coordinator success before its own durable completion`() {
        val automatic = CloudUploadState.Confirmed("one", "project", continuation = true)
        val manual = CloudUploadState.Confirmed("one", "project")
        // Changing a checkbox cannot change the provenance of an earlier result.
        assertFalse(showCoordinatorConfirmation(automatic, "one", "project"))
        assertFalse(showCoordinatorConfirmation(automatic, "other", "project"))
        assertTrue(showCoordinatorConfirmation(manual, "one", "project"))
        assertFalse(showCoordinatorConfirmation(manual, "other", "project"))
        assertFalse(showCoordinatorConfirmation(manual, "one", "other"))
    }
    @Test fun `partial and busy rejection cannot hide an active upload`() {
        val running = CloudUploadState.Running("one", "project", 12, 100)
        for (kind in listOf("partial", "busy")) {
            val failure = CloudUploadState.Failed("two", "other", kind, "failure")
            assertEquals(running, preserveActiveUpload(running, failure))
            assertEquals(failure, preserveActiveUpload(CloudUploadState.Idle, failure))
        }
    }
    @Test fun `cancelled operation keeps gate until worker finishes and suppresses stale progress`() {
        val gate = UploadOperationGate(); val first = gate.acquire()!!
        gate.workerStarted(first)
        var delivered = 0
        gate.deliverProgress(first) { delivered++ }
        gate.stopProgress(first)
        gate.operationFinished(first)
        gate.deliverProgress(first) { delivered++ }
        assertEquals(1, delivered)
        assertNull(gate.acquire())
        gate.workerFinished(first)
        val second = gate.acquire()
        assertNotNull(second)
        gate.workerFinished(first) // A stale worker must not unlock the new owner.
        assertNull(gate.acquire())
        gate.operationFinished(second!!)
        assertNotNull(gate.acquire())
    }
    @Test fun `worker completion before coroutine completion does not release gate early`() {
        val gate = UploadOperationGate(); val ticket = gate.acquire()!!
        gate.workerStarted(ticket); gate.workerFinished(ticket)
        assertNull(gate.acquire())
        gate.operationFinished(ticket)
        assertNotNull(gate.acquire())
    }
}
