package de.kontrakurs.kontraprompter.cloud

import java.util.concurrent.atomic.AtomicReference

/** An old request must not invalidate a newer login. */
internal class SessionCookie {
    private val value = AtomicReference<String?>(null)
    fun get(): String? = value.get()
    fun set(cookie: String?) = value.set(cookie)
    fun invalidate(snapshot: String) { value.compareAndSet(snapshot, null) }
}
