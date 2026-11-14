package de.kontrakurs.kontraprompter.camera

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One instance belongs to one activation/session. The snapshot is captured inside the poll lock. */
internal class SerialCameraPoll {
    private val mutex = Mutex()
    private var sequence = 0L
    private var closed = false

    /** [exchange] must not return until its actual transport has terminated, even after cancellation. */
    suspend fun <S, R> poll(snapshot: (Long) -> S, exchange: suspend (S) -> R): R = mutex.withLock {
        check(!closed) { "Camera polling is closed" }
        check(sequence < 9_007_199_254_740_991L) { "Camera poll sequence exhausted" }
        val report = snapshot(++sequence)
        exchange(report)
    }

    /** Wait for the actual in-flight transport before allowing a new owner. */
    suspend fun closeAndDrain() = mutex.withLock { closed = true }
}
