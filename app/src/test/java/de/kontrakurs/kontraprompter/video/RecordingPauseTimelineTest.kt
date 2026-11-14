package de.kontrakurs.kontraprompter.video

import org.junit.Assert.*
import org.junit.Test

class RecordingPauseTimelineTest {
    @Test fun `pause removes same interval for camera and microphone`() {
        val time = RecordingPauseTimeline(1_000_000_000L)
        assertEquals(500_000_000L, time.project(1_500_000_000L)!!)
        assertTrue(time.pause(2_000_000_000L))
        assertNull(time.project(2_100_000_000L))
        assertNull(time.project(1_990_000_000L, 20_000_000L))
        assertTrue(time.resume(5_000_000_000L))
        assertEquals(1_100_000_000L, time.project(5_100_000_000L)!!)
        assertEquals(1_100_000_000L, time.project(5_100_000_000L, 20_000_000L)!!)
        assertNull(time.project(2_100_000_000L)) // late frame from the pause
        assertEquals(990_000_000L, time.project(1_990_000_000L)!!) // late pre-pause frame
    }
    @Test fun `multiple pauses accumulate without changing earlier timestamps`() {
        val time = RecordingPauseTimeline(1_000L)
        assertTrue(time.pause(2_000L)); assertTrue(time.resume(3_000L))
        assertTrue(time.pause(5_000L)); assertTrue(time.resume(7_000L))
        assertEquals(500L, time.project(1_500L)!!)
        assertEquals(2_000L, time.project(4_000L)!!)
        assertNull(time.project(6_000L))
        assertEquals(3_500L, time.project(7_500L)!!)
    }
    @Test fun `invalid transitions do not move boundaries`() {
        val time = RecordingPauseTimeline(100L)
        assertFalse(time.resume(200L)); assertTrue(time.pause(200L))
        assertFalse(time.pause(300L)); assertFalse(time.resume(200L)); assertTrue(time.isPaused())
        assertTrue(time.resume(300L)); assertFalse(time.pause(299L))
        assertFalse(time.isPaused()); assertNull(time.project(250L)); assertEquals(100L, time.project(300L)!!)
    }
}
