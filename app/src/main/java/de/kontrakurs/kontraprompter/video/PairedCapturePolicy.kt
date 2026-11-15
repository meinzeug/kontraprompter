package de.kontrakurs.kontraprompter.video

internal const val PAIRED_DISCONNECTED_MESSAGE = "Kameraverbindung getrennt. Aufnahmezustand unbekannt."

/** A fresh, validated camera status replaces only the stale disconnection notice. */
internal fun pairedMessageAfterStatus(previous: String?, failure: String?): String? =
    failure ?: previous.takeUnless { it == PAIRED_DISCONNECTED_MESSAGE }

internal fun shutterRequestMatches(expectedPhase: String, expectedId: String?, phase: String, recordingId: String?, pending: Boolean, connectionCurrent: Boolean): Boolean =
    connectionCurrent && !pending && phase == expectedPhase && recordingId == expectedId

/** The viewer waits for a definitive camera status and never repeats an uncertain command. */
internal fun pairedActionAllowed(action: String, phase: String, pending: Boolean): Boolean {
    if (pending) return false
    return when (action) {
        "start" -> phase in setOf("idle", "ready", "partial", "failed")
        "pause" -> phase == "recording"
        "resume" -> phase == "paused"
        "stop" -> phase in setOf("preparing", "recording", "paused")
        "switch_camera" -> phase in setOf("idle", "ready", "partial", "failed")
        else -> false
    }
}

internal data class PairedCommand(val action: String, val recordingId: String, val projectId: String?, val expectedFront: Boolean? = null)

/** Replayed request IDs never execute twice or acknowledge a different payload as accepted. */
internal class PairedCommandLedger(private val capacity: Int = 256) {
    private val handled = LinkedHashMap<String, Pair<PairedCommand, Boolean>>()
    init { require(capacity > 0) }
    @Synchronized fun execute(id: String, command: PairedCommand, action: () -> Boolean): Boolean {
        handled[id]?.let { return it.first == command && it.second }
        val result = action()
        handled[id] = command to result
        if (handled.size > capacity) handled.remove(handled.keys.first())
        return result
    }
}

/** Late acknowledgements and out-of-order statuses never override the current request/state. */
internal fun isCurrentCaptureAck(pendingRequest: String?, acknowledgement: String): Boolean =
    pendingRequest != null && pendingRequest == acknowledgement

internal fun isNewCaptureStatus(revision: Long, previous: Long): Boolean =
    revision >= 0 && revision > previous

/** A switch ACK means admission only; a correlated snapshot confirms its actual state. */
internal fun cameraAckNeedsStatus(action: String?, accepted: Boolean): Boolean =
    action == "switch_camera" && accepted

internal fun cameraStatusAnswersPending(action: String?, pendingId: String?, queryId: String?): Boolean =
    action == "switch_camera" && pendingId != null && pendingId == queryId
