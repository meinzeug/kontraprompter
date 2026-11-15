package de.kontrakurs.kontraprompter.video

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Test-WIP only: a bounded copy of already-recorded PCM, never another microphone. */
internal data class PcmBlock(val recordingId: String, val generation: Long, val firstSample: Long,
                             val captureTimeNs: Long, val sampleRate: Int, val samples: ByteBuffer)

internal class PcmTee : AutoCloseable {
    private val ownerLock = Any()
    private val listeners = AtomicReference<Array<Subscription>>(emptyArray())
    private val epoch = AtomicLong()
    private val exhausted = AtomicBoolean(false)
    @Volatile private var closed = false
    val generation: Long get() = if (exhausted.get()) Long.MIN_VALUE else epoch.get()
    /** A malformed or failed analysis path stays unavailable until a fresh tee is created. */
    fun disable() { exhausted.set(true); invalidateFences(listeners.get()) }

    private fun invalidateFences(snapshot: Array<Subscription>) {
        var index = 0
        while (index < snapshot.size) { snapshot[index].invalidateFence(); index++ }
    }

    /** The analysis side must discard results after this epoch changes. */
    fun invalidate() {
        invalidateFences(listeners.get())
        while (true) {
            val current = epoch.get()
            if (current == Long.MAX_VALUE) { disable(); return }
            if (epoch.compareAndSet(current, current + 1)) break
        }
        // Include a subscription that raced the generation transition.
        invalidateFences(listeners.get())
    }

    /** Slot storage is allocated here, never in the recorder's audio loop. */
    fun subscribe(capacity: Int = 8, fence: SpeechLocalDeliveryFence? = null): Subscription = synchronized(ownerLock) {
        require(capacity in 1..16)
        val before = epoch.get()
        val old = listeners.get(); check(!closed && !exhausted.get() && old.size < 2) { "Audioanalyse nicht verfügbar" }
        check(fence == null || fence.matchesGeneration(before)) { "Audioepoche stimmt nicht" }
        Subscription(capacity, fence, { if (exhausted.get()) Long.MIN_VALUE else epoch.get() }) { remove(it) }.also {
            listeners.set(old + it)
            if (exhausted.get() || epoch.get() != before) it.invalidateFence()
        }
    }
    private fun remove(subscription: Subscription) = synchronized(ownerLock) {
        listeners.set(listeners.get().filterNot { it === subscription }.toTypedArray())
    }

    /** No waiting or PCM allocation: a slow subscriber drops its own analysis block. */
    fun publish(recordingId: String, generation: Long, firstSample: Long, captureTimeNs: Long,
                sampleRate: Int, bytes: ByteArray, length: Int) {
        val snapshot = listeners.get()
        if (snapshot.isEmpty() || closed || exhausted.get() || epoch.get() != generation) return
        var i = 0
        while (i < snapshot.size) {
            snapshot[i].offer(recordingId, generation, firstSample, captureTimeNs, sampleRate, bytes, length)
            i++
        }
    }

    override fun close() {
        val snapshot = synchronized(ownerLock) {
            if (closed) return
            closed = true; invalidate(); listeners.getAndSet(emptyArray())
        }
        snapshot.forEach { it.close() }
    }

    internal class Subscription(capacity: Int, private val fence: SpeechLocalDeliveryFence?, private val currentGeneration: () -> Long,
                                private val remove: (Subscription) -> Unit) : AutoCloseable {
        private class Slot {
            val bytes = ByteArray(8192)
            var length = 0; var recordingId = ""; var generation = 0L
            var firstSample = 0L; var captureTimeNs = 0L; var sampleRate = 0
        }
        private val lock = ReentrantLock()
        private val slots = Array(capacity) { Slot() }
        private val lost = AtomicLong()
        private var head = 0; private var size = 0; @Volatile private var closed = false
        val dropped: Long get() = lost.get()
        internal fun invalidateFence() { fence?.invalidate() }
        private fun markLoss() { invalidateFence(); lost.incrementAndGet() }
        /** A snapshot check only; UI delivery still requires its own atomic fence. */
        fun isCurrent(block: PcmBlock): Boolean = !closed && lost.get() == 0L && block.generation == currentGeneration()

        internal fun offer(recordingId: String, generation: Long, firstSample: Long, captureTimeNs: Long,
                           sampleRate: Int, bytes: ByteArray, length: Int) {
            if (!lock.tryLock()) { markLoss(); return }
            try {
                if (closed || lost.get() != 0L || generation != currentGeneration()) return
                while (size > 0 && slots[head].generation != currentGeneration()) { head = (head + 1) % slots.size; size-- }
                if (length !in 2..minOf(bytes.size, 8192) || length % 2 != 0 || firstSample < 0 || captureTimeNs < 0 || sampleRate != 48_000 || size == slots.size) {
                    markLoss(); return
                }
                val slot = slots[(head + size) % slots.size]
                System.arraycopy(bytes, 0, slot.bytes, 0, length)
                slot.length = length; slot.recordingId = recordingId; slot.generation = generation
                slot.firstSample = firstSample; slot.captureTimeNs = captureTimeNs; slot.sampleRate = sampleRate
                size++
            } finally { lock.unlock() }
        }
        /** All allocations are on the analysis worker, not in the recorder thread. */
        fun poll(): PcmBlock? = lock.withLock {
            while (size > 0 && slots[head].generation != currentGeneration()) { head = (head + 1) % slots.size; size-- }
            if (closed || lost.get() != 0L || size == 0) return null
            val slot = slots[head]
            val data = ByteBuffer.wrap(slot.bytes.copyOf(slot.length)).asReadOnlyBuffer().order(ByteOrder.nativeOrder())
            val block = PcmBlock(slot.recordingId, slot.generation, slot.firstSample, slot.captureTimeNs, slot.sampleRate, data)
            if (!isCurrent(block)) return null
            head = (head + 1) % slots.size; size--
            block
        }
        override fun close() {
            invalidateFence()
            lock.withLock { closed = true; size = 0 }
            remove(this)
        }
    }
}
