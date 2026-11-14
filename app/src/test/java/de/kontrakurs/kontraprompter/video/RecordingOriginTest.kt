package de.kontrakurs.kontraprompter.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class RecordingOriginTest {
    private val recording = "00000000-0000-4000-8000-000000000001"
    private val project = "00000000-0000-4000-8000-000000000002"
    private val other = "00000000-0000-4000-8000-000000000003"

    @Test fun historicalManifestsRetainConservativeWebClaim() {
        assertEquals(RecordingOrigin.MANUAL, RecordingOrigin.read(null, null))
        assertEquals(RecordingOrigin.WEB, RecordingOrigin.read(null, project))
        assertEquals(recording, cameraClaimRecordingId(RecordingOrigin.WEB, recording, project, project))
    }

    @Test fun explicitLanProjectDoesNotPretendToHaveWebDeviceClaim() {
        assertEquals(RecordingOrigin.LAN, RecordingOrigin.read("lan", project))
        assertNull(cameraClaimRecordingId(RecordingOrigin.LAN, recording, project, project))
        assertNull(cameraClaimRecordingId(RecordingOrigin.MANUAL, recording, null, project))
        assertNull(cameraClaimRecordingId(RecordingOrigin.WEB, recording, project, other))
    }

    @Test fun malformedOrContradictoryOriginsAreRejected() {
        for ((wire, projectId) in listOf("unknown" to project, "" to project, "manual" to project, "lan" to null, "web" to null)) {
            assertThrows(IllegalArgumentException::class.java) { RecordingOrigin.read(wire, projectId) }
        }
        assertThrows(IllegalArgumentException::class.java) { cameraClaimRecordingId(RecordingOrigin.LAN, recording, null, project) }
        assertThrows(IllegalArgumentException::class.java) { cameraClaimRecordingId(RecordingOrigin.MANUAL, recording, project, project) }
    }
}
