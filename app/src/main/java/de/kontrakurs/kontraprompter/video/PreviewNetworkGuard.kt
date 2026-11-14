package de.kontrakurs.kontraprompter.video

import java.util.concurrent.atomic.AtomicBoolean

/** A transient change invalidates this preview permanently, even if the old network returns. */
internal class PreviewNetworkGuard<T>(private val expected: T, private val onInvalidated: () -> Unit) {
    private val valid = AtomicBoolean(true)

    fun isValid(): Boolean = valid.get()

    fun observe(actual: T?) {
        if (actual != expected) invalidate()
    }

    fun invalidate() {
        if (valid.compareAndSet(true, false)) onInvalidated()
    }
}
