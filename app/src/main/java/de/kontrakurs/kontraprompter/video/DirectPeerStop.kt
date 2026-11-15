package de.kontrakurs.kontraprompter.video

import java.util.concurrent.atomic.AtomicReference

/** Never declares a native close complete until the executor callback returns. */
internal class DirectPeerStop(private val submit: (() -> Unit) -> Boolean, private val closePeer: () -> Boolean, private val onState: (State) -> Unit = {}) {
    enum class State { IDLE, PENDING, CLOSED, NO_PEER, FAILED }
    private val current = AtomicReference(State.IDLE)
    fun state(): State = current.get()
    fun request() {
        if (!current.compareAndSet(State.IDLE, State.PENDING)) return
        runCatching { onState(State.PENDING) }
        val submitted = runCatching {
            submit {
                val result = runCatching { closePeer() }
                val next = if (result.isFailure) State.FAILED else if (result.getOrThrow()) State.CLOSED else State.NO_PEER
                if (current.compareAndSet(State.PENDING, next)) runCatching { onState(next) }
            }
        }.getOrDefault(false)
        if (!submitted && current.compareAndSet(State.PENDING, State.FAILED)) runCatching { onState(State.FAILED) }
    }
}
