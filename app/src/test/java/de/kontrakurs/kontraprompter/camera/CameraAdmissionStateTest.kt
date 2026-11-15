package de.kontrakurs.kontraprompter.camera

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class CameraAdmissionStateTest {
    private fun id() = UUID.randomUUID().toString()
    private fun start() = CameraCommand(id(), "record_start", id(), id())
    @Test fun admissionPrecedesSuccessAndCrashIsUncertainWithoutReplay() {
        val command = start()
        val pending = decideCameraCommand(CameraDecisionState(), command, false, true, twoPhase = true)
        assertEquals("admitting", pending.result)
        assertEquals("start", pending.action)
        assertFalse(pending.state.decisions.single().pendingAck)
        assertEquals(6, cameraJournalVersion(pending.state))
        assertNull(decideCameraCommand(pending.state, command, false, true, twoPhase = true).action)
        val recovered = recoverCameraAdmissions(pending.state)
        assertEquals("uncertain", recovered.decisions.single().result)
        assertTrue(recovered.decisions.single().pendingAck)
        assertNull(decideCameraCommand(recovered, command, false, true, twoPhase = true).action)
        assertEquals(recovered, recoverCameraAdmissions(recovered))
    }
    @Test fun admissionAndFailureAreDistinguishedAndLateAcksCannotEraseRevision() {
        val command = start()
        val pending = decideCameraCommand(CameraDecisionState(), command, false, true, twoPhase = true).state
        for ((admitted,result) in listOf(true to "accepted",false to "uncertain")) {
            val finished = finishCameraAdmission(pending, command, admitted)
            assertEquals(result, finished.decisions.single().result)
            assertTrue(finished.decisions.single().pendingAck)
            val late = confirmCameraAcks(finished, mapOf(command.id to 1L))
            assertTrue(late.decisions.single().pendingAck)
            assertNull(decideCameraCommand(late, command, false, true, twoPhase = true).action)
        }
    }
    @Test fun stopRemainsAnExplicitRepeatableRecoveryPath() {
        val command = start()
        val pending = decideCameraCommand(CameraDecisionState(), command, false, true, twoPhase = true).state
        val recovered = recoverCameraAdmissions(pending)
        val stop = CameraCommand(id(), "record_stop", command.recordingId, null)
        val first = decideCameraCommand(recovered, stop, false, false, twoPhase = true)
        assertEquals("stop", first.action)
        assertEquals("accepted", first.result)
        assertEquals("stop", decideCameraCommand(first.state, stop, false, false, twoPhase = true).action)
    }
}
