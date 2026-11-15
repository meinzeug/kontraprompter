package de.kontrakurs.kontraprompter.video

import org.junit.Assert.*
import org.junit.Test

class ExperimentalCameraReadinessTest {
    @Test fun `switch hides preview until a fresh frame after completion`() {
        val state = ExperimentalCameraReadiness()
        assertFalse(state.isReady()); state.frame(); assertTrue(state.isReady())
        val token = state.beginSwitch()!!
        assertFalse(state.isReady()); state.frame(); assertFalse(state.isReady())
        assertTrue(state.finishSwitch(token)); assertFalse(state.isReady())
        state.frame(); assertTrue(state.isReady())
    }
    @Test fun `stale completion cannot expose preview during a newer switch`() {
        val state = ExperimentalCameraReadiness()
        val first = state.beginSwitch()!!; assertTrue(state.finishSwitch(first))
        val second = state.beginSwitch()!!; assertFalse(state.finishSwitch(first))
        state.frame(); assertFalse(state.isReady()); assertNull(state.beginSwitch())
        assertTrue(state.finishSwitch(second)); assertFalse(state.isReady())
        state.frame(); assertTrue(state.isReady())
    }
    @Test fun `camera failure invalidates current readiness`() {
        val state = ExperimentalCameraReadiness()
        state.frame(); state.invalidate(); assertFalse(state.isReady())
        state.frame(); assertFalse(state.isReady())
        state.opening(); assertFalse(state.isReady()); state.frame(); assertTrue(state.isReady())
    }
}
