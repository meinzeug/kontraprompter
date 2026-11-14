package de.kontrakurs.kontraprompter

import org.junit.Assert.*
import org.junit.Test

/** Tests the complete pure path for the synthetic external keyboard used on Samsung. */
class HidShutterPipelineTest {
    @Test fun selectedExternalEnterStartsOnlyAfterCompletedPressAndWindow() {
        val descriptor = "synthetic-hid"
        val tracker = ShutterKeyTracker()
        val clicks = ShutterClickMachine()
        val link = Any()
        val context = ShutterContext("idle", null, null, ShutterLink(link), 11, descriptor)
        assertTrue(isSelfieTrigger(true, false, 0x18d2, 0x2c42, "KontraPrompter Synthetic HID Test", descriptor, descriptor, 66))
        assertEquals(ShutterKeyDecision.CONSUME, tracker.event(true, descriptor, 66, 0, 0, 1_000))
        assertEquals(ShutterKeyDecision.SHORT_PRESS, tracker.event(true, descriptor, 66, 1, 0, 1_060))
        val wait = clicks.press(descriptor, 66, 1_060, context) as ShutterPress.Wait
        assertNull(clicks.expire(descriptor, wait.token, 1_459))
        val expired = clicks.expire(descriptor, wait.token, 1_460)
        assertEquals(context, expired)
        assertTrue(shutterContextCurrent(context, "idle", null, false, true, true))
        assertEquals(ShutterAction.START, shutterAction(expired!!, ShutterGesture.SINGLE))
    }

    @Test fun differentDeviceOrLostConnectionCannotDispatchDelayedStart() {
        val context = ShutterContext("idle", null, null, ShutterLink(Any()), 11, "synthetic-hid")
        assertFalse(shutterContextCurrent(context, "idle", null, false, false, true))
        assertFalse(shutterContextCurrent(context, "idle", null, false, true, false))
        assertFalse(isSelfieTrigger(true, false, 0x18d2, 0x2c42, "KontraPrompter Synthetic HID Test", "synthetic-hid", "other", 66))
    }
}
