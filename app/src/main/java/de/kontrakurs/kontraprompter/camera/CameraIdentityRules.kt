package de.kontrakurs.kontraprompter.camera

import java.util.UUID

internal fun cameraJournalFileName(deviceId: String): String {
    require(UUID.fromString(deviceId).toString() == deviceId)
    return "decisions-$deviceId.json"
}

internal fun legacyCameraJournalBelongsTo(ownerId: String?, deviceId: String): Boolean = ownerId != null && ownerId == deviceId

/** Only a never-published identity transaction may initialize a missing journal. */
internal fun mayInitializeCameraJournal(journalState: String, keyState: String): Boolean =
    journalState == "initializing" || (journalState.isEmpty() && keyState == "creating")

/** Active recording must remain remotely stoppable even if older files cannot be verified. */
internal fun cameraReportBeforeReconciliation(phase: String): Boolean = phase in setOf("preparing", "recording", "finalizing")
