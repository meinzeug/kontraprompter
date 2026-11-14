package de.kontrakurs.kontraprompter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecordingScrollSyncTest {
    @Test fun confirmedRecordingStartsAndPausedOrFinishedStopsPlayback() {
        assertEquals(true, recordingScrollPlayback("recording"))
        for (phase in listOf("paused", "finalizing", "ready", "partial", "failed"))
            assertEquals(false, recordingScrollPlayback(phase))
    }

    @Test fun uncertainAndStartingStatesDoNotChangePlayback() {
        for (phase in listOf("unknown", "idle", "preparing", "unexpected"))
            assertNull(recordingScrollPlayback(phase))
    }
}
