package de.kontrakurs.kontraprompter.video

import java.util.concurrent.atomic.AtomicLong

/** A connection-loss event invalidates callbacks, never the camera or recorder. */
internal class PrimaryPeerGeneration {
    private val value = AtomicLong(1)
    fun current(): Long = value.get()
    fun isCurrent(generation: Long): Boolean = value.get() == generation
    fun invalidate() {
        value.updateAndGet { previous -> check(previous != Long.MAX_VALUE); previous + 1 }
    }
}
