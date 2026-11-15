package de.kontrakurs.kontraprompter.video

import org.junit.Assert.*
import org.junit.Test

class CameraCaptureProfileTest {
    @Test fun `release ignores every experimental request`() {
        for (raw in listOf(false, true)) for (fps in listOf(false, true)) {
            val result = CameraCaptureProfile.select(false, raw, fps)
            assertEquals(CameraCaptureProfile(false, false, 640, 480), result)
            assertEquals(1_800_000, CameraCaptureProfile.recorderBitrate(result.raw))
        }
    }
    @Test fun `debug defaults to the existing track path and camera dimensions`() {
        assertEquals(CameraCaptureProfile(false, false, 640, 480), CameraCaptureProfile.select(true, false, false))
        assertEquals(CameraCaptureProfile(false, false, 640, 480), CameraCaptureProfile.select(true, false, true))
    }
    @Test fun `raw and fixed fps need explicit debug requests`() {
        assertEquals(CameraCaptureProfile(true, false, 1280, 720), CameraCaptureProfile.select(true, true, false))
        assertEquals(CameraCaptureProfile(true, true, 1280, 720), CameraCaptureProfile.select(true, true, true))
        assertEquals(3_800_000, CameraCaptureProfile.recorderBitrate(true))
    }
}
