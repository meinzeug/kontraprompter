package de.kontrakurs.kontraprompter.video

import java.util.concurrent.atomic.AtomicReference

/** Unwired WIP: one local scope, permanently invalidated on pause/loss/close. */
internal class SpeechLocalDeliveryFence(
    private val channel: Any,
    private val recordingId: String,
    private val scriptRevision: String,
    private val audioGeneration: Long,
    private val wordCount: Int,
    anchor: Int,
) {
    private class Active(val sequence: Long, val position: Int)
    // Constructed before subscription, never allocated on the audio thread.
    private val stopped = Any()
    private val state = AtomicReference<Any>(Active(0, anchor))
    init { require(recordingId.isNotBlank() && scriptRevision.isNotBlank() && audioGeneration >= 0 && wordCount in 1..200_000 && anchor in 0..wordCount) }

    /** No callbacks, locks, waits or allocation; it is safe for the producer to invoke. */
    fun invalidate() { state.set(stopped) }
    fun matchesGeneration(generation: Long): Boolean = generation == audioGeneration && state.get() is Active
    fun positionOrNull(): Int? = (state.get() as? Active)?.position
    fun apply(channel: Any, recordingId: String, scriptRevision: String, audioGeneration: Long,
              sequence: Long, position: Int): Boolean {
        if (channel !== this.channel || recordingId != this.recordingId || scriptRevision != this.scriptRevision ||
            audioGeneration != this.audioGeneration || sequence <= 0 || position !in 1..wordCount) return false
        while (true) {
            val old = state.get() as? Active ?: return false
            if (sequence <= old.sequence || position <= old.position) return false
            if (state.compareAndSet(old, Active(sequence, position))) return true
        }
    }
}
