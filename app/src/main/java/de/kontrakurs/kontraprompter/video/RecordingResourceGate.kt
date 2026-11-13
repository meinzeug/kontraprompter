package de.kontrakurs.kontraprompter.video

/** A timed-out codec must never overlap a new capture in the same process. */
internal class RecordingResourceGate {
    private var current: Long? = null
    private var generation = 0L
    @Synchronized fun tryAcquire(): Long? {
        if (current != null) return null
        check(generation < Long.MAX_VALUE)
        return (++generation).also { current = it }
    }
    @Synchronized fun isAvailable(): Boolean = current == null
    @Synchronized fun release(owner: Long) { check(current == owner); current = null }
}

/** Cleanup and gate release are never attempted while an encoder worker is alive. */
internal fun awaitWorkersBeforeCleanup(workers: List<Thread>, cleanup: () -> Boolean, released: () -> Unit) {
    workers.forEach { worker ->
        while (worker.isAlive) {
            try { worker.join() } catch (_: InterruptedException) { /* retain ownership until exit */ }
        }
    }
    if (cleanup()) released()
}
