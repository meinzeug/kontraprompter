package de.kontrakurs.kontraprompter.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CameraFpsPolicyTest {
    private fun range(min: Int, max: Int) = CameraFpsPolicy.FpsRange(min, max)

    @Test fun `fixed 20 is preferred to flexible ranges`() {
        val selected = CameraFpsPolicy.chooseMinimum(listOf(range(5, 30), range(20, 30), range(20, 20)), 20)
        assertEquals(CameraFpsPolicy.Decision.MinimumRequested(range(20, 20)), selected)
    }
    @Test fun `flexible supported range can meet minimum without requiring fixed mode`() {
        val selected = CameraFpsPolicy.chooseMinimum(listOf(range(5, 30), range(24, 30), range(20, 30)), 20)
        assertEquals(CameraFpsPolicy.Decision.MinimumRequested(range(20, 30)), selected)
    }
    @Test fun `higher minimum falls back rather than demanding unverified encoder capacity`() {
        assertEquals(CameraFpsPolicy.Decision.UseExistingCapturePolicy, CameraFpsPolicy.chooseMinimum(listOf(range(24, 30)), 20))
    }
    @Test fun `no supported minimum leaves existing capture policy unchanged`() {
        assertEquals(CameraFpsPolicy.Decision.UseExistingCapturePolicy, CameraFpsPolicy.chooseMinimum(listOf(range(5, 30), range(10, 15)), 20))
        assertEquals(CameraFpsPolicy.Decision.UseExistingCapturePolicy, CameraFpsPolicy.chooseMinimum(emptyList(), 20))
    }
    @Test fun `malformed ranges are ignored and minimum must be positive`() {
        assertEquals(CameraFpsPolicy.Decision.UseExistingCapturePolicy, CameraFpsPolicy.chooseMinimum(listOf(range(0, 30), range(30, 20), range(-1, 50)), 20))
        assertThrows(IllegalArgumentException::class.java) { CameraFpsPolicy.chooseMinimum(listOf(range(20, 20)), 0) }
    }
}
