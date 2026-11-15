package de.kontrakurs.kontraprompter.cloud

import java.util.concurrent.atomic.AtomicReference

/** Session changes serialize with short, session-bound durable upload decisions. */
internal class SessionCookie {
    private val value = AtomicReference<String?>(null)
    private val changes = Any()
    fun get(): String? = value.get()
    fun set(cookie: String?) { synchronized(changes) { value.set(cookie) } }
    fun invalidate(snapshot: String) { synchronized(changes) { value.compareAndSet(snapshot, null) } }
    fun <T> withCurrent(snapshot: String, block: () -> T): T? = synchronized(changes) {
        if (value.get() != snapshot) null else block()
    }
}
