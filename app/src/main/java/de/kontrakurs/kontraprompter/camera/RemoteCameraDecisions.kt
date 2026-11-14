package de.kontrakurs.kontraprompter.camera

import java.util.UUID

/** The result acknowledges a durable decision, never completion of recording or upload. */
internal data class CameraCommand(val id: String, val type: String, val recordingId: String, val projectId: String?)
internal data class CameraDecision(val command: CameraCommand, val result: String, val pendingAck: Boolean, val revision: Long = 1)
internal data class CameraCapture(val recordingId: String, val projectId: String?, val phase: String, val reportedPhase: String? = null, val sizeBytes: Long? = null, val sha256: String? = null, val quarantinedPhase: String? = null)
internal data class CameraDecisionState(val decisions: List<CameraDecision> = emptyList(), val captures: List<CameraCapture> = emptyList(), val sequence: Long = 0)
internal data class CameraDecisionOutcome(val state: CameraDecisionState, val result: String, val action: String?)

private val activeCapturePhases = setOf("accepted", "preparing", "recording", "finalizing", "recovering")
internal val allowedCapturePhases = activeCapturePhases + setOf("ready", "partial", "error")
internal const val MAX_CAMERA_DECISIONS = 10_000
internal const val MAX_CAMERA_SEQUENCE = 9_007_199_254_740_991L
internal fun cameraJournalVersion(state: CameraDecisionState): Int = when {
    state.decisions.any { it.command.type in setOf("record_pause", "record_resume", "camera_switch") } -> 3
    state.captures.any { it.quarantinedPhase != null } -> 2
    else -> 1
}
internal fun cameraJournalQuarantinesValid(version: Int, captures: List<CameraCapture>): Boolean {
    if (version !in 1..3 || version == 2 && captures.none { it.quarantinedPhase != null }) return false
    return captures.all { capture ->
        val phase = capture.quarantinedPhase
        phase == null || (version >= 2 && phase == capture.phase && capture.projectId != null && capture.reportedPhase != phase &&
            when (phase) {
                "error" -> capture.sizeBytes == null && capture.sha256 == null
                "partial" -> capture.sizeBytes != null && capture.sizeBytes in 1..1_073_741_824L && capture.sha256?.matches(Regex("^[0-9a-f]{64}$")) == true
                else -> false
            })
    }
}

internal fun cameraUploadEligible(capture: CameraCapture): Boolean = capture.quarantinedPhase == null && capture.phase == "ready" && capture.reportedPhase == "ready" &&
    capture.projectId != null && capture.sizeBytes != null && capture.sha256 != null

internal fun reserveCameraSequence(state: CameraDecisionState): CameraDecisionState {
    check(state.sequence in 0 until MAX_CAMERA_SEQUENCE) { "Camera sequence exhausted" }
    return state.copy(sequence = state.sequence + 1)
}

private fun canonicalUuid(value: String): Boolean = runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)

/** A caller persists [CameraDecisionOutcome.state] before ACK or executing [CameraDecisionOutcome.action]. */
internal fun decideCameraCommand(state: CameraDecisionState, command: CameraCommand, expired: Boolean, canStart: Boolean, canPause: Boolean = false, canResume: Boolean = false, canSwitch: Boolean = false): CameraDecisionOutcome {
    require(canonicalUuid(command.id) && canonicalUuid(command.recordingId))
    require(command.type in setOf("record_start", "record_stop", "record_pause", "record_resume", "camera_switch"))
    require(if (command.type == "record_start") command.projectId != null && canonicalUuid(command.projectId) else command.projectId == null)
    require(command.type != "camera_switch" || command.recordingId == command.id)
    state.decisions.firstOrNull { it.command.id == command.id }?.let { previous ->
        check(previous.command == command) { "A command ID was reused for different data" }
        check(previous.revision < Long.MAX_VALUE) { "Camera decision revision exhausted" }
        // An expired delivery is not a refusal of the persistent STOP intent.
        if (command.type == "record_stop" && previous.result == "expired" && !expired) {
            val renewed = decideCameraCommand(state.copy(decisions = state.decisions.filterNot { it.command.id == command.id }), command, false, canStart)
            return renewed.copy(state = renewed.state.copy(decisions = renewed.state.decisions.map { if (it.command.id == command.id) it.copy(revision = previous.revision + 1) else it }))
        }
        val repeatStop = previous.result == "accepted" && command.type == "record_stop" &&
            state.captures.any { it.recordingId == command.recordingId && it.phase in activeCapturePhases }
        return CameraDecisionOutcome(state.copy(decisions = state.decisions.map { if (it.command.id == command.id) it.copy(pendingAck = true, revision = previous.revision + 1) else it }), previous.result, if (repeatStop) "stop" else null)
    }
    val capture = state.captures.firstOrNull { it.recordingId == command.recordingId }
    val reservedStop = command.type == "record_stop" && capture?.phase in activeCapturePhases
    val capacity = if (state.captures.any { it.phase in activeCapturePhases } && !reservedStop) MAX_CAMERA_DECISIONS - 1 else MAX_CAMERA_DECISIONS
    check(state.decisions.size < capacity) { "Camera command journal is full" }
    val eligible = when (command.type) {
        "record_start" -> canStart && state.decisions.size < MAX_CAMERA_DECISIONS - 1 && capture == null &&
            state.captures.none { it.phase in activeCapturePhases || it.reportedPhase != it.phase || it.quarantinedPhase != null }
        "record_pause" -> capture?.phase == "recording" && capture.projectId != null && canPause
        "record_resume" -> capture?.phase == "recording" && capture.projectId != null && canResume
        "camera_switch" -> canSwitch && state.captures.none { it.phase in activeCapturePhases || it.reportedPhase != it.phase || it.quarantinedPhase != null }
        else -> capture != null
    }
    val result = when { expired -> "expired"; !eligible -> "rejected"; else -> "accepted" }
    val action = if (result != "accepted") null else when (command.type) {
        "record_start" -> "start"
        "record_pause" -> "pause"
        "record_resume" -> "resume"
        "camera_switch" -> "switch"
        else -> if (capture!!.phase in activeCapturePhases) "stop" else null
    }
    val captures = if (action == "start") state.captures + CameraCapture(command.recordingId, command.projectId!!, "accepted") else state.captures
    return CameraDecisionOutcome(state.copy(decisions = state.decisions + CameraDecision(command, result, true), captures = captures), result, action)
}

/** After a process restart no in-memory recorder can be assumed to be alive. */
internal fun recoverCameraCaptures(state: CameraDecisionState): CameraDecisionState = state.copy(
    captures = state.captures.map { if (it.phase in activeCapturePhases) it.copy(phase = "recovering", reportedPhase = null) else it }
)

internal fun updateCameraCapture(state: CameraDecisionState, recordingId: String, phase: String): CameraDecisionState {
    require(phase in allowedCapturePhases)
    val existing = state.captures.firstOrNull { it.recordingId == recordingId } ?: error("Unknown recording")
    val permitted = when (existing.phase) {
        "accepted" -> allowedCapturePhases
        "preparing" -> setOf("preparing", "recording", "finalizing", "ready", "partial", "error")
        "recording" -> setOf("recording", "finalizing", "ready", "partial", "error")
        "finalizing" -> setOf("finalizing", "ready", "partial", "error")
        "recovering" -> setOf("recovering", "ready", "partial", "error")
        else -> setOf(existing.phase)
    }
    check(phase in permitted) { "Stale or invalid recording transition" }
    return state.copy(captures = state.captures.map { if (it.recordingId == recordingId && it.phase != phase) it.copy(phase = phase, reportedPhase = null) else it })
}

internal fun updateCameraCaptureProof(state: CameraDecisionState, recordingId: String, phase: String, sizeBytes: Long, sha256: String): CameraDecisionState {
    require(phase == "ready" || phase == "partial")
    require(sizeBytes in 1..1_073_741_824L && sha256.matches(Regex("^[0-9a-f]{64}$")))
    val old = state.captures.firstOrNull { it.recordingId == recordingId } ?: error("Unknown recording")
    check(old.sizeBytes == null && old.sha256 == null || (old.phase == phase && old.sizeBytes == sizeBytes && old.sha256 == sha256)) { "Recording proof cannot change" }
    val updated = updateCameraCapture(state, recordingId, phase)
    return updated.copy(captures = updated.captures.map { if (it.recordingId == recordingId) it.copy(sizeBytes = sizeBytes, sha256 = sha256) else it })
}

internal fun confirmCameraCaptureReport(state: CameraDecisionState, recordingId: String, phase: String): CameraDecisionState = state.copy(
    captures = state.captures.map { if (it.recordingId == recordingId && it.phase == phase && it.quarantinedPhase == null) it.copy(reportedPhase = phase) else it }
)

/** Persist only the exact, still-unaccepted terminal report; never manufacture an ACK. */
internal fun quarantineCameraReport(state: CameraDecisionState, recordingId: String, projectId: String, phase: String, sizeBytes: Long?, sha256: String?): CameraDecisionState {
    require(phase == "partial" || phase == "error")
    val capture = state.captures.firstOrNull { it.recordingId == recordingId } ?: return state
    if (capture.projectId != projectId || capture.phase != phase || capture.reportedPhase == phase || capture.sizeBytes != sizeBytes || capture.sha256 != sha256) return state
    if (capture.quarantinedPhase == phase) return state
    check(capture.quarantinedPhase == null) { "Another camera conflict is already recorded" }
    return state.copy(captures = state.captures.map { if (it.recordingId == recordingId) it.copy(quarantinedPhase = phase) else it })
}

internal fun hasExactCameraQuarantine(state: CameraDecisionState, recordingId: String, projectId: String, phase: String, sizeBytes: Long?, sha256: String?): Boolean =
    state.captures.any { it.recordingId == recordingId && it.projectId == projectId && it.phase == phase &&
        it.quarantinedPhase == phase && it.reportedPhase != phase && it.sizeBytes == sizeBytes && it.sha256 == sha256 }

/** Only the identifiers needed to locate a rejected report; no file or journal is changed. */
internal data class CameraConflictDetails(val recordingId: String, val projectId: String, val phase: String)
internal fun cameraConflictDetails(state: CameraDecisionState): List<CameraConflictDetails> =
    state.captures.mapNotNull { capture ->
        val project = capture.projectId
        if (capture.quarantinedPhase != null && project != null)
            CameraConflictDetails(capture.recordingId, project, capture.quarantinedPhase)
        else null
    }

/** Quarantine leaves the report unaccepted but stops it monopolizing polling. */
internal fun pendingCameraCaptureReport(state: CameraDecisionState): CameraCapture? = state.captures.lastOrNull {
    it.phase in setOf("ready", "partial", "error") && it.reportedPhase != it.phase && it.quarantinedPhase == null
}

internal fun invalidateCameraCaptureReport(state: CameraDecisionState, recordingId: String, phase: String): CameraDecisionState = state.copy(
    captures = state.captures.map { if (it.recordingId == recordingId && it.phase == phase) it.copy(reportedPhase = null) else it }
)

/** Clear only ACKs actually included in a successfully completed poll. */
internal fun confirmCameraAcks(state: CameraDecisionState, revisions: Map<String, Long>): CameraDecisionState = state.copy(
    decisions = state.decisions.map { if (revisions[it.command.id] == it.revision) it.copy(pendingAck = false) else it }
)
