package de.kontrakurs.kontraprompter.speech

import java.util.concurrent.atomic.AtomicReference

/** Test-only model: loss, channel/seek invalidation and visible position share ONE CAS state. */
internal class SpeechAtomicPositionFixture(channel: Any, recording: String, scriptRevision: String, generation: Long, drops: Long, anchor: Int) {
    data class State(val channel: Any, val recording: String, val scriptRevision: String, val generation: Long, val drops: Long, val epoch: Long,
                     val sequence: Long, val position: Int, val active: Boolean)
    private val state = AtomicReference(State(channel, recording, scriptRevision, generation, drops, 1, 0, anchor, true))
    fun snapshot(): State = state.get()
    fun invalidate() {
        while (true) {
            val old = state.get()
            if (!old.active || state.compareAndSet(old, old.copy(active = false))) return
        }
    }
    /** The returned state is the position source itself, not a later unchecked UI callback. */
    fun apply(message: SpeechFollowHarness.Position): Boolean {
        while (true) {
            val old = state.get()
            if (!old.active || message.channel !== old.channel || message.recording != old.recording ||
                message.scriptRevision != old.scriptRevision || message.audioGeneration != old.generation ||
                message.dropCount != old.drops || message.revision != old.epoch ||
                message.sequence <= old.sequence || message.word <= old.position) return false
            if (state.compareAndSet(old, old.copy(sequence = message.sequence, position = message.word))) return true
        }
    }
}
