package de.kontrakurs.kontraprompter.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PreviewCloseStateTest {
    @Test fun pendingCloseBlocksUntilTheSamePeerConfirms() {
        val state = PreviewCloseState()
        state.begin("peer-a", "Webvorschau aus")
        assertEquals("peer-a", state.id)
        assertNull(state.complete("peer-b", true))
        assertEquals("peer-a", state.id)
        assertEquals("Webvorschau aus", state.complete("peer-a", true))
        assertNull(state.id)
    }

    @Test fun failureNeverReportsClosedAndKeepsTheBarrier() {
        val state = PreviewCloseState()
        state.begin("peer-a", "Webvorschau beendet")
        assertEquals("Webvorschau: Trennung nicht bestätigt", state.complete("peer-a", false))
        assertEquals("peer-a", state.id)
    }

    @Test fun disablingWhileCloseIsPendingChangesOnlyTheFinalMessage() {
        val state = PreviewCloseState()
        state.begin("peer-a", "Dashboard getrennt")
        state.afterClose("Webvorschau aus")
        assertEquals("peer-a", state.id)
        assertEquals("Webvorschau aus", state.complete("peer-a", true))
        assertNull(state.id)
    }
}
