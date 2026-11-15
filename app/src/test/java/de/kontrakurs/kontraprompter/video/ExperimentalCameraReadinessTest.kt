package de.kontrakurs.kontraprompter.video

import org.junit.Assert.*
import org.junit.Test

class ExperimentalCameraReadinessTest {
    @Test fun `switch hides preview until a fresh frame after completion`() {
        val state = ExperimentalCameraReadiness()
        assertFalse(state.isReady()); state.frame(); assertTrue(state.isReady())
        val token = state.beginSwitch()!!
        assertFalse(state.isReady()); state.frame(); assertFalse(state.isReady())
        assertTrue(state.finishSwitch(token, true)); assertFalse(state.isReady())
        state.frame(); assertTrue(state.isReady())
    }
    @Test fun `stale completion cannot expose preview during a newer switch`() {
        val state = ExperimentalCameraReadiness()
        val first = state.beginSwitch()!!; assertTrue(state.finishSwitch(first, true))
        val second = state.beginSwitch()!!; assertFalse(state.finishSwitch(first, true))
        state.frame(); assertFalse(state.isReady()); assertNull(state.beginSwitch())
        assertTrue(state.finishSwitch(second, true)); assertFalse(state.isReady())
        state.frame(); assertTrue(state.isReady())
    }
    @Test fun `camera failure invalidates current readiness`() {
        val state = ExperimentalCameraReadiness()
        state.frame(); state.invalidate(); assertFalse(state.isReady())
        state.frame(); assertFalse(state.isReady())
        state.opening(); state.frame(); assertFalse(state.isReady())
        val token = state.beginSwitch()!!; assertTrue(state.finishSwitch(token, true))
        state.frame(); assertTrue(state.isReady())
    }
    @Test fun `failure during switch and late opening cannot restore readiness`() {
        val state = ExperimentalCameraReadiness(); state.frame()
        val token = state.beginSwitch()!!; state.invalidate(); state.opening()
        assertTrue(state.finishSwitch(token, true)); state.frame(); assertFalse(state.isReady())
    }
    @Test fun `failed switch never accepts a frame as proof of success`() {
        val state = ExperimentalCameraReadiness(); state.frame()
        val token = state.beginSwitch()!!; assertTrue(state.finishSwitch(token, false))
        state.opening(); state.frame(); assertFalse(state.isReady())
    }
}
