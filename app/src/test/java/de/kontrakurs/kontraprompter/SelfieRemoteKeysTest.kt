package de.kontrakurs.kontraprompter

import org.junit.Assert.*
import org.junit.Test

class SelfieRemoteKeysTest {
    @Test fun `actual P007 supports both volume keys without internal key interception`() {
        fun check(external: Boolean, code: Int) = isSelfieTrigger(external, false, 0x0a5c, 1, "ITFIT Selfie P007", "bluetooth-p007", null, code)
        assertTrue(check(true, 24)); assertTrue(check(true, 25)); assertFalse(check(false, 24)); assertFalse(check(false, 25))
        assertFalse(isSelfieTrigger(true, true, 0x0a5c, 1, "ITFIT Selfie P007", "virtual", null, 25))
    }
    @Test fun `other external volume remotes require their explicit descriptor`() {
        fun check(selected: String?) = isSelfieTrigger(true, false, 10, 20, "Other", "other-remote", selected, 25)
        assertFalse(check(null)); assertFalse(check("different")); assertTrue(check("other-remote"))
        assertFalse(isSelfieTrigger(false, false, 10, 20, "Other", "other-remote", "other-remote", 25))
    }
    @Test fun `one press toggles only on first down and consumes release and repeat`() {
        assertEquals(ShutterKeyDecision.TRIGGER, shutterKeyDecision(true, 0, 0))
        assertEquals(ShutterKeyDecision.CONSUME, shutterKeyDecision(true, 0, 1))
        assertEquals(ShutterKeyDecision.CONSUME, shutterKeyDecision(true, 1, 0))
        assertEquals(ShutterKeyDecision.PASS, shutterKeyDecision(false, 0, 0))
    }
    @Test fun `near simultaneous codes from one device toggle only once`() {
        val debounce = ShutterDebounce()
        assertTrue(debounce.accept("p007", 1000))
        assertFalse(debounce.accept("p007", 1010))
        assertTrue(debounce.accept("p007", 1250))
        assertFalse(debounce.accept("p007", 1240))
        assertTrue(debounce.accept("other", 1260))
    }
    @Test fun `external dedicated camera and media keys are supported`() {
        for (key in listOf(27, 80, 85, 126, 127, 130)) {
            assertTrue(isSelfieTrigger(true, false, 7, 8, "External", "external", null, key))
            assertFalse(isSelfieTrigger(false, false, 7, 8, "Internal", "internal", null, key))
        }
        assertFalse(isSelfieTrigger(true, false, 7, 8, "External", "external", null, 29))
    }
}
