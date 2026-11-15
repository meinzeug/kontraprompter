package de.kontrakurs.kontraprompter.cloud

import java.util.concurrent.atomic.AtomicReference

/** Session changes serialize with short, session-bound durable upload decisions. */
internal class SessionCookie {
    // Identity changes on every set, even when a server issues the same cookie twice.
    class Snapshot internal constructor(val cookie: String)
    private val value = AtomicReference<Snapshot?>(null)
    private val changes = Any()
    private val onSet = mutableListOf<() -> Unit>()
    private val onInvalidated = mutableListOf<() -> Unit>()
    fun onSessionInvalidated(action: () -> Unit) { synchronized(changes) { onInvalidated.add(action) } }
    fun onSessionSet(action: () -> Unit) { synchronized(changes) { onSet.add(action) } }
    fun get(): String? = value.get()?.cookie
    fun snapshot(): Snapshot? = value.get()
    fun set(cookie: String?) { synchronized(changes) { value.set(cookie?.let(::Snapshot)); onSet.forEach { it() } } }
    fun invalidate(snapshot: String) { synchronized(changes) { if (value.get()?.cookie == snapshot) { value.set(null); onInvalidated.forEach { it() } } } }
    fun invalidate(snapshot: Snapshot) { synchronized(changes) { if (value.get() === snapshot) { value.set(null); onInvalidated.forEach { it() } } } }
    fun matches(snapshot: Snapshot): Boolean = value.get() === snapshot
    fun <T> withoutSession(block: () -> T): T? = synchronized(changes) { if (value.get() == null) block() else null }
    fun <T> withCurrent(snapshot: Snapshot, block: () -> T): T? = synchronized(changes) {
        if (value.get() !== snapshot) null else block()
    }
    // Existing string-bound callers retain their earlier semantics.
    fun <T> withCurrent(snapshot: String, block: () -> T): T? = synchronized(changes) {
        if (value.get()?.cookie != snapshot) null else block()
    }
}
