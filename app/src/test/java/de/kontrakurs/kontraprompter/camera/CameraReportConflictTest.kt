package de.kontrakurs.kontraprompter.camera

import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class CameraReportConflictTest {
    private fun id() = UUID.randomUUID().toString()
    private val hash = "a".repeat(64)

    @Test fun `only an exactly bound terminal conflict permits quarantine`() {
        val device = id(); val report = CameraReport("partial", id(), id(), 42, hash)
        val request = CameraPollRequest(12, report, emptyList())
        val fields: Map<String, Any?> = mapOf("code" to "camera_late_report_conflict", "deviceId" to device,
            "recordingId" to report.recordingId, "projectId" to report.projectId, "phase" to report.phase,
            "sequence" to 12L, "sizeBytes" to 42L, "sha256" to hash)
        assertTrue(matchesLateReportConflict(device, request, fields))
        for ((key, value) in mapOf<String, Any?>("code" to "other", "deviceId" to id(), "recordingId" to id(),
            "projectId" to id(), "phase" to "error", "sequence" to 13L, "sizeBytes" to 43L, "sha256" to "b".repeat(64))) {
            assertFalse(key, matchesLateReportConflict(device, request, fields + (key to value)))
        }
        assertFalse(matchesLateReportConflict(device, request, fields + ("sequence" to "12")))
        assertFalse(matchesLateReportConflict(device, request, fields + ("sizeBytes" to 42.0)))
        assertFalse(matchesLateReportConflict(device, request.copy(report = report.copy(phase = "recording")), fields))
        assertFalse(matchesLateReportConflict(device, request.copy(report = report.copy(projectId = null)), fields))
    }

    @Test fun `error conflict requires explicit null proof and correct request`() {
        val device = id(); val report = CameraReport("error", id(), id()); val request = CameraPollRequest(9, report, emptyList())
        val fields: Map<String, Any?> = mapOf("code" to "camera_late_report_conflict", "deviceId" to device,
            "recordingId" to report.recordingId, "projectId" to report.projectId, "phase" to "error",
            "sequence" to 9, "sizeBytes" to null, "sha256" to null)
        assertTrue(matchesLateReportConflict(device, request, fields))
        assertFalse(matchesLateReportConflict(device, request, fields + ("sizeBytes" to 2)))
        assertFalse(matchesLateReportConflict(device, request.copy(report = report.copy(sizeBytes = 2)), fields))
    }

    @Test fun `quarantine preserves unresolved report and blocks new starts without blocking stop`() {
        val old = CameraCapture(id(), id(), "partial", null, 42, hash)
        val running = CameraCapture(id(), id(), "recording")
        val initial = CameraDecisionState(captures = listOf(old, running), sequence = 5)
        val quarantined = quarantineCameraReport(initial, old.recordingId, old.projectId!!, old.phase, old.sizeBytes, old.sha256)
        assertEquals(2, cameraJournalVersion(quarantined))
        assertEquals("partial", quarantined.captures.first().quarantinedPhase)
        assertNull(quarantined.captures.first().reportedPhase)
        assertNull(pendingCameraCaptureReport(quarantined))
        assertNull(confirmCameraCaptureReport(quarantined, old.recordingId, "partial").captures.first().reportedPhase)
        assertTrue(hasExactCameraQuarantine(quarantined, old.recordingId, old.projectId, "partial", 42, hash))
        assertFalse(hasExactCameraQuarantine(quarantined, old.recordingId, old.projectId, "partial", 43, hash))
        assertFalse(hasExactCameraQuarantine(quarantined, old.recordingId, old.projectId, "partial", 42, "b".repeat(64)))
        val stop = CameraCommand(id(), "record_stop", running.recordingId, null)
        assertEquals("stop", decideCameraCommand(quarantined, stop, false, true).action)
        val start = CameraCommand(id(), "record_start", id(), id())
        assertEquals("rejected", decideCameraCommand(quarantined, start, false, true).result)
        assertFalse(cameraUploadEligible(quarantined.captures.first()))
        assertEquals(6L, reserveCameraSequence(quarantined).sequence)
        assertEquals("partial", recoverCameraCaptures(quarantined).captures.first().quarantinedPhase)
        assertEquals(quarantined, quarantineCameraReport(quarantined, old.recordingId, old.projectId, old.phase, old.sizeBytes, old.sha256))
    }

    @Test fun `stale mismatched and already accepted reports cannot be quarantined`() {
        val capture = CameraCapture(id(), id(), "partial", null, 42, hash)
        val state = CameraDecisionState(captures = listOf(capture))
        assertEquals(1, cameraJournalVersion(state))
        assertEquals(state, quarantineCameraReport(state, capture.recordingId, id(), "partial", 42, hash))
        assertEquals(state, quarantineCameraReport(state, capture.recordingId, capture.projectId!!, "partial", 43, hash))
        assertEquals(state, quarantineCameraReport(state, capture.recordingId, capture.projectId, "partial", 42, "b".repeat(64)))
        val accepted = confirmCameraCaptureReport(state, capture.recordingId, capture.phase)
        assertEquals(accepted, quarantineCameraReport(accepted, capture.recordingId, capture.projectId, "partial", 42, hash))
    }

    @Test fun `other pending reports remain eligible after quarantine`() {
        val first = CameraCapture(id(), id(), "error")
        val second = CameraCapture(id(), id(), "error")
        val original = CameraDecisionState(captures = listOf(first, second))
        val isolated = quarantineCameraReport(original, second.recordingId, second.projectId!!, "error", null, null)
        assertEquals(first, pendingCameraCaptureReport(isolated))
        assertEquals("error", isolated.captures.last().quarantinedPhase)
        assertNull(isolated.captures.last().reportedPhase)
    }

    @Test fun `rejected conflict poll leaves stop acknowledgements pending`() {
        val capture = CameraCapture(id(), id(), "error")
        val stop = CameraCommand(id(), "record_stop", id(), null)
        val state = CameraDecisionState(decisions = listOf(CameraDecision(stop, "accepted", true, 4)), captures = listOf(capture))
        val isolated = quarantineCameraReport(state, capture.recordingId, capture.projectId!!, "error", null, null)
        assertTrue(isolated.decisions.single().pendingAck)
        assertEquals(4L, isolated.decisions.single().revision)
        assertNull(isolated.captures.single().reportedPhase)
        assertEquals("error", isolated.captures.single().quarantinedPhase)
    }

    @Test fun `journal version and quarantine validation fail closed across restart and downgrade`() {
        val capture = CameraCapture(id(), id(), "partial", null, 42, hash)
        val original = CameraDecisionState(captures = listOf(capture), sequence = 19)
        assertTrue(cameraJournalQuarantinesValid(1, original.captures))
        val saved = quarantineCameraReport(original, capture.recordingId, capture.projectId!!, "partial", 42, hash)
        assertEquals(2, cameraJournalVersion(saved))
        assertTrue(cameraJournalQuarantinesValid(2, saved.captures))
        assertFalse(cameraJournalQuarantinesValid(1, saved.captures))
        assertFalse(cameraJournalQuarantinesValid(2, original.captures))
        assertFalse(cameraJournalQuarantinesValid(2, listOf(saved.captures.single().copy(sizeBytes = null))))
        assertFalse(cameraJournalQuarantinesValid(2, listOf(saved.captures.single().copy(reportedPhase = "partial"))))
        assertFalse(cameraJournalQuarantinesValid(2, listOf(saved.captures.single().copy(projectId = null))))
        assertEquals(20L, reserveCameraSequence(recoverCameraCaptures(saved)).sequence)
    }

    @Test fun `logout cancellation does not interrupt a received conflict while it is saved`() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var persisted = false; var unsafeStop = false
        val poll = launch {
            persistLateReportConflict(
                persist = { entered.complete(Unit); release.await(); persisted = true },
                onUnsafe = { unsafeStop = true },
            )
        }
        withTimeout(2000) { entered.await() }; poll.cancel(); release.complete(Unit)
        withTimeout(2000) { poll.join() }
        assertTrue(persisted); assertFalse(unsafeStop)
    }

    @Test fun `a failed conflict journal write invokes the unsafe recording safeguard`() = runBlocking {
        var unsafeStop = false
        val result = runCatching {
            persistLateReportConflict(persist = { throw IllegalStateException("test write error") }, onUnsafe = { unsafeStop = true })
        }
        assertTrue(result.isFailure); assertTrue(unsafeStop)
    }

    @Test fun `an unmatched conflict fails closed without clearing pending acknowledgements`() = runBlocking {
        val capture = CameraCapture(id(), id(), "partial", null, 42, hash)
        val stop = CameraCommand(id(), "record_stop", id(), null)
        val initial = CameraDecisionState(decisions = listOf(CameraDecision(stop, "accepted", true)), captures = listOf(capture))
        var unsafeStop = false
        val result = runCatching {
            persistLateReportConflict(
                persist = { check(hasExactCameraQuarantine(initial, capture.recordingId, capture.projectId!!, "partial", 42, hash)) },
                onUnsafe = { unsafeStop = true },
            )
        }
        assertTrue(result.isFailure); assertTrue(unsafeStop)
        assertTrue(initial.decisions.single().pendingAck); assertNull(initial.captures.single().reportedPhase)
    }

    @Test fun `logout during the blocking exchange still journals its returned conflict`() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CountDownLatch(1)
        var persisted = false; var unsafeStop = false
        val poll = launch {
            exchangeWithLateReportConflict(
                exchange = { entered.complete(Unit); check(release.await(2, TimeUnit.SECONDS)); throw CameraLateReportConflictException("test-device") },
                persist = { conflict -> assertEquals("test-device", conflict.deviceId); persisted = true }, onUnsafe = { unsafeStop = true },
            )
        }
        try {
            withTimeout(2000) { entered.await() }; poll.cancel(); release.countDown()
            withTimeout(2000) { poll.join() }
            assertTrue(persisted); assertFalse(unsafeStop)
        } finally { release.countDown() }
    }

    @Test fun `an unrelated transport conflict is never silently quarantined`() = runBlocking {
        var persisted = false
        val result = runCatching {
            exchangeWithLateReportConflict(exchange = { throw IllegalStateException("other 409") }, persist = { persisted = true }, onUnsafe = {})
        }
        assertTrue(result.isFailure); assertFalse(persisted)
    }

    @Test fun `a late response cannot be stored in another device journal`() {
        val oldDevice = id(); val newDevice = id()
        requireCameraConflictJournalIdentity(oldDevice, oldDevice)
        assertThrows(IllegalStateException::class.java) { requireCameraConflictJournalIdentity(newDevice, oldDevice) }
    }
}
