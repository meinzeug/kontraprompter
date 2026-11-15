package de.kontrakurs.kontraprompter.video

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoCapabilityCheckTest {
    @Test fun missingCapabilitiesNeverSelectEncoderOrInvokeProbe() {
        assertFalse(supportsVideo<String>(null) { error("probe must not be called") })
    }

    @Test fun presentCapabilitiesRequireSuccessfulSizeAndRateProbe() {
        assertTrue(supportsVideo("1280x720@20") { it == "1280x720@20" })
        assertFalse(supportsVideo("640x480@20") { it == "1280x720@20" })
    }
}
