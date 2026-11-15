package de.kontrakurs.kontraprompter.speech

import java.util.concurrent.atomic.AtomicReference

/** Test-only receiver model. Remote invalidation must first reach this receiver. */
internal class SpeechDeliveryFence {
    data class Scope(val channel: Any, val recording: String, val script: String, val audioGeneration: Long, val wordCount: Int)
    data class State(val epoch: Long, val scope: Scope?, val active: Boolean, val sequence: Long, val position: Int)
    data class Message(val channel: Any, val recording: String, val script: String,
                       val audioGeneration: Long, val epoch: Long, val sequence: Long, val position: Int)
    private val state = AtomicReference(State(0, null, false, 0, 0))
    fun snapshot(): State = state.get()
    private fun next(epoch: Long): Long { check(epoch < Long.MAX_VALUE); return epoch + 1 }

    /** Owner starts a fresh scope; old result messages cannot inherit its epoch. */
    fun start(scope: Scope, anchor: Int): State {
        require(scope.recording.isNotBlank() && scope.script.isNotBlank() && scope.audioGeneration >= 0 && scope.wordCount in 1..200_000 && anchor in 0..scope.wordCount)
        while (true) {
            val old = state.get(); val fresh = State(next(old.epoch), scope, true, 0, anchor)
            if (state.compareAndSet(old, fresh)) return fresh
        }
    }
    /** Pause, disconnect, known PCM loss or stop invalidates results in the same state as position. */
    fun invalidate(): State {
        while (true) {
            val old = state.get(); val changed = old.copy(epoch = next(old.epoch), active = false)
            if (state.compareAndSet(old, changed)) return changed
        }
    }
    /** A manual seek is an atomic position change and invalidation, not a delayed UI callback. */
    fun seek(anchor: Int): State {
        require(anchor >= 0)
        while (true) {
            val old = state.get(); require(anchor <= (old.scope?.wordCount ?: 0))
            val changed = old.copy(epoch = next(old.epoch), active = false, position = anchor)
            if (state.compareAndSet(old, changed)) return changed
        }
    }
    /** Only this state is renderable; no unchecked message-to-UI callback is invoked. */
    fun apply(message: Message): Boolean {
        while (true) {
            val old = state.get(); val scope = old.scope ?: return false
            if (!old.active || message.channel !== scope.channel || message.recording != scope.recording ||
                message.script != scope.script || message.audioGeneration != scope.audioGeneration || message.epoch != old.epoch ||
                message.sequence <= old.sequence || message.position <= old.position || message.position > scope.wordCount) return false
            if (state.compareAndSet(old, old.copy(sequence = message.sequence, position = message.position))) return true
        }
    }
}
