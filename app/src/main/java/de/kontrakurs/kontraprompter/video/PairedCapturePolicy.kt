package de.kontrakurs.kontraprompter.video

/** The viewer waits for a definitive camera status and never repeats an uncertain command. */
internal fun pairedActionAllowed(action: String, phase: String, pending: Boolean): Boolean {
    if (pending) return false
    return when (action) {
        "start" -> phase in setOf("idle", "ready", "partial", "failed")
        "pause" -> phase == "recording"
        "resume" -> phase == "paused"
        "stop" -> phase in setOf("preparing", "recording", "paused")
        else -> false
    }
}

internal data class PairedCommand(val action: String, val recordingId: String, val projectId: String?)

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
