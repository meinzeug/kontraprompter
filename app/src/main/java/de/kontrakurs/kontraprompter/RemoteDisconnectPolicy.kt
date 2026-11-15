package de.kontrakurs.kontraprompter

/** A connected client can leave without recovery only for a terminal camera status. */
internal fun mayDisconnectRemote(ready: Boolean, pending: Boolean, phase: String): Boolean =
    ready && !pending && phase in setOf("idle", "ready", "partial")

internal fun mayStartCamera(sessionPresent: Boolean): Boolean = !sessionPresent

/** No authority by itself: the domain must still fence state-changing actions. */
internal data class RecoverySnapshot<T : Any, L, P>(val session: T, val generation: Long, val lan: L, val paired: P) {
    fun matches(current: T?, currentGeneration: Long, currentLan: L, currentPaired: P): Boolean =
        session === current && generation == currentGeneration && lan == currentLan && paired == currentPaired
}
