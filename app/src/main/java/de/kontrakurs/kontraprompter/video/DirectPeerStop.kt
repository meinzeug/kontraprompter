package de.kontrakurs.kontraprompter.video

import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

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

/** Old unresolved closes remain visible when a later session creates a new peer. */
internal class DirectPeerStopTracker {
    private val pending = mutableSetOf<Any>()
    private var uncertain = false
    private val _state = MutableStateFlow(DirectPeerStop.State.IDLE)
    val state: StateFlow<DirectPeerStop.State> = _state

    /** A new session may clear an old success, never an outstanding uncertainty. */
    @Synchronized fun beginSession() {
        if (!uncertain && pending.isEmpty()) _state.value = DirectPeerStop.State.IDLE
    }

    @Synchronized fun update(owner: Any, status: DirectPeerStop.State) {
        when (status) {
            DirectPeerStop.State.IDLE -> return
            DirectPeerStop.State.PENDING -> {
                if (owner !in pending && pending.size >= 32) uncertain = true else pending.add(owner)
            }
            DirectPeerStop.State.CLOSED, DirectPeerStop.State.NO_PEER -> if (!pending.remove(owner)) return
            DirectPeerStop.State.FAILED -> {
                if (!pending.remove(owner)) return
                uncertain = true
            }
        }
        _state.value = when {
            uncertain -> DirectPeerStop.State.FAILED
            pending.isNotEmpty() -> DirectPeerStop.State.PENDING
            else -> status
        }
    }
}

internal object ProcessDirectPeerStops {
    private val tracker = DirectPeerStopTracker()
    val state: StateFlow<DirectPeerStop.State> = tracker.state
    fun beginSession() = tracker.beginSession()
    fun update(owner: Any, status: DirectPeerStop.State) = tracker.update(owner, status)
}
