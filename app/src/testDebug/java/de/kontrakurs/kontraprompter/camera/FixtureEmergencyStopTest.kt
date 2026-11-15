package de.kontrakurs.kontraprompter.camera

import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class FixtureEmergencyStopTest {
    @Test fun repeatedDeadlineTicksStopALateAdmittedTakeAndKeepItsId() {
        val id = UUID.randomUUID().toString(); val calls = mutableListOf<String>()
        var active = false
        val guard = FixtureEmergencyStop(id) { requested -> calls.add(requested); if (active) active = false }
        guard.tick() // A timed-out START has not yet arrived.
        active = true // A late START is admitted despite the first stop.
        guard.tick(); guard.tick()
        assertFalse(active); assertEquals(listOf(id, id, id), calls)
    }
    @Test fun transientStopFailureDoesNotSuppressTheNextTick() {
        val id = UUID.randomUUID().toString(); var calls = 0
        val guard = FixtureEmergencyStop(id) { calls++; if (calls == 1) error("synthetic lock failure") }
        guard.tick(); guard.tick(); assertEquals(2, calls)
    }
    @Test fun malformedAndNoncanonicalTakeIdsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { FixtureEmergencyStop("invalid") {} }
        assertThrows(IllegalArgumentException::class.java) { FixtureEmergencyStop(UUID.randomUUID().toString().uppercase()) {} }
    }
}
