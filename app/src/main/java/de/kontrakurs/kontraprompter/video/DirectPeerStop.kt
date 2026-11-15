package de.kontrakurs.kontraprompter.video

import java.util.concurrent.atomic.AtomicReference

/** Never declares a native close complete until the executor callback returns. */
internal class DirectPeerStop(private val submit: (() -> Unit) -> Boolean, private val closePeer: () -> Unit) {
    enum class State { IDLE, PENDING, CLOSED, FAILED }
    private val current = AtomicReference(State.IDLE)
    fun state(): State = current.get()
    fun request() {
        if (!current.compareAndSet(State.IDLE, State.PENDING)) return
        val submitted = runCatching {
            submit {
                val result = runCatching { closePeer() }
                current.set(if (result.isSuccess) State.CLOSED else State.FAILED)
            }
        }.getOrDefault(false)
        if (!submitted) current.compareAndSet(State.PENDING, State.FAILED)
    }
}
