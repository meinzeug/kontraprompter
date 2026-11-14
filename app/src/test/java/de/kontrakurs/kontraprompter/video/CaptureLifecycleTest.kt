package de.kontrakurs.kontraprompter.video

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CaptureLifecycleTest {
    @Test fun `late pairing callback cannot create a capture after disconnect or close`() {
        assertTrue(mayCreateVideoPipeline(closed = false, pairingReady = true, videoExists = false))
        assertFalse(mayCreateVideoPipeline(closed = false, pairingReady = false, videoExists = false))
        assertFalse(mayCreateVideoPipeline(closed = true, pairingReady = true, videoExists = false))
    }

    @Test fun `pairing callback never creates a second capture while an existing owner survives`() {
        assertFalse(mayCreateVideoPipeline(closed = false, pairingReady = true, videoExists = true))
        assertFalse(mayCreateVideoPipeline(closed = false, pairingReady = false, videoExists = true))
        assertFalse(mayCreateVideoPipeline(closed = true, pairingReady = true, videoExists = true))
    }

    @Test fun `connection loss never releases camera during preparation capture or finalization`() {
        val active = listOf(LocalRecording.Preparing("id"), LocalRecording.Recording("id"), LocalRecording.Finalizing("id"))
        active.forEach { state ->
            assertFalse(shouldReleaseVideo(state, closed = false, disconnected = true))
            assertFalse(shouldReleaseVideo(state, closed = true, disconnected = true))
        }
    }
    @Test fun `camera releases after success or failure only when session ended`() {
        val completed = listOf(LocalRecording.Idle, LocalRecording.Ready("id", File("test"), 1, 1, "USER"), LocalRecording.Failed("failed"))
        completed.forEach { state ->
            assertFalse(shouldReleaseVideo(state, closed = false, disconnected = false))
            assertTrue(shouldReleaseVideo(state, closed = false, disconnected = true))
            assertTrue(shouldReleaseVideo(state, closed = true, disconnected = false))
        }
    }
    @Test fun `blocked cleanup keeps camera until matching recorder releases resources`() {
        val blocked = LocalRecording.Failed("Encoder ist noch aktiv", blocked = true, id = "first")
        assertFalse(shouldReleaseVideo(blocked, closed = true, disconnected = true))
        val stale = releaseBlockedRecording(blocked, "other")
        assertFalse(shouldReleaseVideo(stale, closed = true, disconnected = true))
        val cleaned = releaseBlockedRecording(blocked, "first")
        assertTrue(shouldReleaseVideo(cleaned, closed = true, disconnected = true))
    }
}
