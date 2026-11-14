package de.kontrakurs.kontraprompter.camera

import org.junit.Assert.*
import org.junit.Test

class CameraIdentityRulesTest {
    @Test fun `new identity never selects the old identity journal`() {
        val old = "90b9acc5-239f-4edc-978a-5f56080ac00b"
        val next = "3e139922-9fc4-46bd-b3e4-d9293c9b5940"
        assertNotEquals(cameraJournalFileName(old), cameraJournalFileName(next))
        assertTrue(legacyCameraJournalBelongsTo(old, old))
        assertFalse(legacyCameraJournalBelongsTo(old, next))
        assertFalse(legacyCameraJournalBelongsTo(null, next))
    }

    @Test fun `journal names reject noncanonical ids and path traversal`() {
        assertThrows(IllegalArgumentException::class.java) { cameraJournalFileName("../../old") }
        assertThrows(IllegalArgumentException::class.java) { cameraJournalFileName("90B9ACC5-239F-4EDC-978A-5F56080AC00B") }
    }

    @Test fun `only unpublished initialization may replace missing journal`() {
        assertTrue(mayInitializeCameraJournal("initializing", "creating"))
        assertTrue(mayInitializeCameraJournal("initializing", "ready"))
        assertTrue(mayInitializeCameraJournal("", "creating"))
        assertFalse(mayInitializeCameraJournal("ready", "ready"))
        assertFalse(mayInitializeCameraJournal("", "ready"))
    }

    @Test fun `active capture is reported before unrelated recovery`() {
        listOf("preparing", "recording", "finalizing").forEach { assertTrue(cameraReportBeforeReconciliation(it)) }
        listOf("ready", "partial", "error", "idle").forEach { assertFalse(cameraReportBeforeReconciliation(it)) }
    }
}
