package de.kontrakurs.kontraprompter.video

/** Only short in-memory checks and state changes belong inside these callbacks. */
internal inline fun attemptIdleClose(guard: Any, busy: () -> Boolean, markClosed: () -> Unit): Boolean = synchronized(guard) {
    if (busy()) false else { markClosed(); true }
}
