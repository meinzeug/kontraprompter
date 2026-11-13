package de.kontrakurs.kontraprompter.video

import org.junit.Assert.*
import org.junit.Test

class RecordingTimelineTest {
    @Test fun `video and microphone samples use the same monotonic origin`() {
        val clock = RecordingTimeline(10_000_000_000L)
        assertEquals(500_000_000L, clock.videoTimestampNs(10_500_000_000L)!!)
        // The timestamp refers to frame 48,000 at 11 seconds; frame 24,000 was captured at 10.5 seconds.
        assertEquals(500_000L, clock.audioTimestampUs(11_000_000_000L, 48_000, 24_000, 48_000)!!)
        assertNull(clock.videoTimestampNs(9_999_999_999L))
    }
    @Test fun `audio interpolation handles negative offsets without premature rounding`() {
        val clock = RecordingTimeline(1_000_000_000L)
        assertEquals(999_979L, clock.audioTimestampUs(2_000_000_000L, 2, 1, 48_000)!!)
        assertEquals(1_000_020L, clock.audioTimestampUs(2_000_000_000L, 1, 2, 48_000)!!)
    }
    @Test fun `sample ledger rejects out of order and oversized data`() {
        val ledger = RecordingSampleLedger(100)
        ledger.add(0, 1, 30); ledger.add(1, 2, 40)
        assertThrows(IllegalStateException::class.java) { ledger.add(0, 1, 1) }
        assertThrows(IllegalStateException::class.java) { ledger.add(1, 3, 31) }
        assertEquals(70L, ledger.snapshot().bytes)
        assertEquals(1L, ledger.snapshot().videoSamples)
        assertEquals(1L, ledger.snapshot().audioSamples)
    }
}
