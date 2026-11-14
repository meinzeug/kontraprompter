package de.kontrakurs.kontraprompter.camera

import org.junit.Assert.*
import org.junit.Test

class CameraPreviewLeaseTest {
    @Test fun `lease counts from request start and is locally capped`() {
        assertEquals(10_000L, previewLeaseDeadline(1_000, 1_500, 12_000))
        assertEquals(3_000L, previewLeaseDeadline(1_000, 1_500, 2_000))
        assertNull(previewLeaseDeadline(1_000, 3_000, 2_000))
        assertNull(previewLeaseDeadline(1_000, 999, 2_000))
        assertNull(previewLeaseDeadline(1_000, 1_001, 12_001))
    }

    @Test fun `stale watchdog cannot revoke replacement or renewed session`() {
        assertTrue(previewLeaseMayExpire("old", 100, "old", 100, 101))
        assertFalse(previewLeaseMayExpire("old", 100, "new", 200, 201))
        assertFalse(previewLeaseMayExpire("old", 100, "old", 200, 201))
        assertFalse(previewLeaseMayExpire("old", 100, null, 0, 201))
    }
}
