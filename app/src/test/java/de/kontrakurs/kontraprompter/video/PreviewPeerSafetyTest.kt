package de.kontrakurs.kontraprompter.video

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewPeerSafetyTest {
    @Test fun unknownCleanupPermanentlyBlocksRestartAndEmptyPeerAcknowledgement() {
        val safety = PreviewPeerSafety()
        assertTrue(safety.mayStart())
        assertTrue(safety.mayConfirmNoPeer())
        // A constructor may fail after native allocation before returning an object,
        // or a retained peer's close may fail. Neither proves native cleanup.
        safety.markCleanupUnknown()
        assertFalse(safety.mayStart())
        assertFalse(safety.mayConfirmNoPeer())
        safety.markCleanupUnknown()
        assertFalse(safety.mayStart())
        assertFalse(safety.mayConfirmNoPeer())
    }
}
