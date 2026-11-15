package de.kontrakurs.kontraprompter.video

import org.junit.Assert.*
import org.junit.Test

class FrameRateDiagnosticsTest {
    @Test fun `empty and single samples have no intervals or fps`() {
        val d = FrameRateDiagnostics()
        assertNull(d.summary().fps)
        d.add(0)
        assertNull(d.summary().p95IntervalMs)
        assertNull(d.summary().fps)
    }

    @Test fun `bounded histogram reports aggregates and rejects stale timestamps`() {
        val d = FrameRateDiagnostics()
        d.add(1_000_000L); d.add(51_000_000L); d.add(121_000_000L)
        d.add(121_000_000L); d.add(-1L)
        val s = d.summary()
        assertEquals(3L, s.frames)
        assertEquals(2L, s.invalidTimestamps)
        assertEquals(50, s.medianIntervalMs)
        assertEquals(70, s.p95IntervalMs)
        assertEquals(70L, s.maxIntervalMs)
        assertEquals(2.0 / .12, s.fps!!, .001)
    }

    @Test fun `long intervals use overflow bucket without losing true maximum`() {
        val d = FrameRateDiagnostics()
        d.add(1); d.add(3_000_000_001L)
        val s = d.summary()
        assertEquals(1001, s.p95IntervalMs)
        assertEquals(3000L, s.maxIntervalMs)
    }

    @Test fun `arrivals, observed pause, rejection and accepted samples stay separate`() {
        val d = CaptureFrameDiagnostics()
        d.arriving(1_000_000, false)
        d.rejected(CaptureFrameDiagnostics.Rejection.PREPARING)
        d.arriving(51_000_000, true)
        d.rejected(CaptureFrameDiagnostics.Rejection.PROJECTED_OUT)
        d.arriving(101_000_000, false)
        d.accepted(1_000_000)
        assertEquals(3L, d.arrivals().frames)
        assertEquals(1L, d.pausedFrames())
        assertEquals(1L, d.rejections()[CaptureFrameDiagnostics.Rejection.PREPARING.ordinal])
        assertEquals(1L, d.rejections()[CaptureFrameDiagnostics.Rejection.PROJECTED_OUT.ordinal])
        assertEquals(1L, d.acceptedFrames().frames)
    }
}
