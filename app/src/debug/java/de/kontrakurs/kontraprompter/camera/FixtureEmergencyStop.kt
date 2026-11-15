package de.kontrakurs.kontraprompter.camera

import java.util.UUID

/** Test package only: a deadline tick must continue to target the same take. */
internal class FixtureEmergencyStop(private val recordingId: String, private val stop: (String) -> Unit) {
    init { require(runCatching { UUID.fromString(recordingId).toString() == recordingId }.getOrDefault(false)) }
    fun tick() { runCatching { stop(recordingId) } }
}
