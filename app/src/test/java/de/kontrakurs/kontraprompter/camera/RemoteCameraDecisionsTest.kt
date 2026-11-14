package de.kontrakurs.kontraprompter.camera

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class RemoteCameraDecisionsTest {
    private fun id() = UUID.randomUUID().toString()
    private fun start(recording: String = id(), project: String = id()) = CameraCommand(id(), "record_start", recording, project)

    @Test fun `a repeated start cannot execute twice and remains bound to its project`() {
        val command = start()
        val first = decideCameraCommand(CameraDecisionState(), command, expired = false, canStart = true)
        assertEquals("start", first.action)
        val repeated = decideCameraCommand(first.state, command, expired = false, canStart = true)
        assertEquals("accepted", repeated.result)
        assertNull(repeated.action)
        assertEquals(command.projectId, repeated.state.captures.single().projectId)
    }

    @Test fun `reusing a command id with different content fails closed`() {
        val command = start()
        val state = decideCameraCommand(CameraDecisionState(), command, false, true).state
        assertThrows(IllegalStateException::class.java) {
            decideCameraCommand(state, command.copy(projectId = id()), false, true)
        }
    }

    @Test fun `a reused recording id never starts a second capture`() {
        val first = start()
        val state = updateCameraCapture(decideCameraCommand(CameraDecisionState(), first, false, true).state, first.recordingId, "ready")
        val next = decideCameraCommand(state, start(recording = first.recordingId), false, true)
        assertEquals("rejected", next.result)
        assertNull(next.action)
    }

    @Test fun `expired stop delivery can be renewed but accepted stop is idempotent`() {
        val begin = start()
        val state = decideCameraCommand(CameraDecisionState(), begin, false, true).state
        val stop = CameraCommand(id(), "record_stop", begin.recordingId, null)
        val expired = decideCameraCommand(state, stop, true, true)
        assertEquals("expired", expired.result)
        assertNull(expired.action)
        val renewed = decideCameraCommand(expired.state, stop, false, true)
        assertEquals("accepted", renewed.result)
        assertEquals("stop", renewed.action)
        val repeated = decideCameraCommand(renewed.state, stop, false, true)
        assertEquals("accepted", repeated.result)
        assertEquals("stop", repeated.action)
        val finished = updateCameraCapture(repeated.state, begin.recordingId, "ready")
        assertNull(decideCameraCommand(finished, stop, false, true).action)
    }

    @Test fun `restart never repeats a possibly started capture`() {
        val command = start()
        val state = decideCameraCommand(CameraDecisionState(), command, false, true).state
        val recovered = recoverCameraCaptures(state)
        assertEquals("recovering", recovered.captures.single().phase)
        assertEquals(command.projectId, recovered.captures.single().projectId)
        assertNull(decideCameraCommand(recovered, command, false, true).action)
        assertThrows(IllegalStateException::class.java) { updateCameraCapture(recovered, command.recordingId, "recording") }
    }

    @Test fun `only acknowledged command ids are cleared`() {
        val first = start()
        val one = decideCameraCommand(CameraDecisionState(), first, false, true).state
        val stop = CameraCommand(id(), "record_stop", first.recordingId, null)
        val both = decideCameraCommand(one, stop, false, true).state
        val result = confirmCameraAcks(both, mapOf(first.id to 1L))
        assertFalse(result.decisions.first { it.command.id == first.id }.pendingAck)
        assertTrue(result.decisions.first { it.command.id == stop.id }.pendingAck)
    }

    @Test fun `old terminal report cannot clear a newer capture phase`() {
        val command = start()
        val running = decideCameraCommand(CameraDecisionState(), command, false, true).state
        val ready = updateCameraCapture(running, command.recordingId, "ready")
        val stale = confirmCameraCaptureReport(ready, command.recordingId, "error")
        assertNull(stale.captures.single().reportedPhase)
        val confirmed = confirmCameraCaptureReport(stale, command.recordingId, "ready")
        assertEquals("ready", confirmed.captures.single().reportedPhase)
        val next = decideCameraCommand(confirmed, start(), false, true)
        assertEquals("start", next.action)
    }

    @Test fun `sequence reservation remains monotonic across restored state and failures`() {
        val beforeSend = reserveCameraSequence(CameraDecisionState())
        assertEquals(1L, beforeSend.sequence)
        val afterRestart = reserveCameraSequence(beforeSend)
        assertEquals(2L, afterRestart.sequence)
        assertThrows(IllegalStateException::class.java) { reserveCameraSequence(afterRestart.copy(sequence = MAX_CAMERA_SEQUENCE)) }
    }

    @Test fun `verified recording proof is bound to capture and immutable on retry`() {
        val command = start()
        val state = decideCameraCommand(CameraDecisionState(), command, false, true).state
        val digest = "a".repeat(64)
        val ready = updateCameraCaptureProof(state, command.recordingId, "ready", 123, digest)
        assertEquals(command.projectId, ready.captures.single().projectId)
        assertEquals(123L, ready.captures.single().sizeBytes!!)
        assertEquals(digest, ready.captures.single().sha256)
        assertEquals(ready, updateCameraCaptureProof(ready, command.recordingId, "ready", 123, digest))
        assertThrows(IllegalStateException::class.java) { updateCameraCaptureProof(ready, command.recordingId, "ready", 123, "b".repeat(64)) }
        assertThrows(IllegalArgumentException::class.java) { updateCameraCaptureProof(state, command.recordingId, "ready", 0, digest) }
        assertThrows(IllegalArgumentException::class.java) { updateCameraCaptureProof(state, command.recordingId, "ready", 123, digest.uppercase()) }
        assertFalse(cameraUploadEligible(ready.captures.single()))
        val confirmed = confirmCameraCaptureReport(ready, command.recordingId, "ready")
        assertTrue(cameraUploadEligible(confirmed.captures.single()))
        assertFalse(cameraUploadEligible(invalidateCameraCaptureReport(confirmed, command.recordingId, "ready").captures.single()))
    }

    @Test fun `old acknowledgement cannot erase a renewed stop decision`() {
        val begin = start()
        val state = decideCameraCommand(CameraDecisionState(), begin, false, true).state
        val stop = CameraCommand(id(), "record_stop", begin.recordingId, null)
        val expired = decideCameraCommand(state, stop, true, true).state
        val renewed = decideCameraCommand(expired, stop, false, true).state
        val afterOldAck = confirmCameraAcks(renewed, mapOf(stop.id to 1L))
        assertTrue(afterOldAck.decisions.first { it.command.id == stop.id }.pendingAck)
        assertEquals("accepted", afterOldAck.decisions.first { it.command.id == stop.id }.result)
        assertEquals("stop", decideCameraCommand(afterOldAck, stop.copy(id = id()), false, true).action)
    }

    @Test fun `start fails closed when camera or microphone is not ready`() {
        val result = decideCameraCommand(CameraDecisionState(), start(), false, false)
        assertEquals("rejected", result.result)
        assertNull(result.action)
        assertTrue(result.state.captures.isEmpty())
    }

    @Test fun `an active recording retains the last journal slot for its stop`() {
        val begin = start()
        val active = decideCameraCommand(CameraDecisionState(), begin, false, true).state
        val filler = (1 until MAX_CAMERA_DECISIONS - 1).map {
            CameraDecision(CameraCommand(id(), "record_stop", id(), null), "rejected", false)
        }
        val almostFull = active.copy(decisions = active.decisions + filler)
        assertThrows(IllegalStateException::class.java) { decideCameraCommand(almostFull, start(), false, true) }
        val stop = CameraCommand(id(), "record_stop", begin.recordingId, null)
        assertEquals("stop", decideCameraCommand(almostFull, stop, false, true).action)
    }
}
