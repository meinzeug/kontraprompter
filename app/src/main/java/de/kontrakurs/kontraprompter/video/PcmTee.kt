package de.kontrakurs.kontraprompter.video

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Immutable PCM view from the existing recording microphone, not a second capture. */
internal data class PcmBlock(
    val recordingId: String,
    val generation: Long,
    val firstSample: Long,
    val captureTimeNs: Long,
    val sampleRate: Int,
    val samples: ByteBuffer,
)

/**
 * Bounded, best-effort analysis tap. A slow consumer loses blocks; it never
 * waits in the recorder thread and can detect gaps from firstSample. All
 * consumers are disabled until explicitly subscribed; samples stay in RAM.
 */
internal class PcmTee : AutoCloseable {
    private val subscribers = CopyOnWriteArrayList<Subscription>()
    private val ownerLock = Any()
    private val epoch = AtomicLong()
    private var closed = false
    val generation: Long get() = epoch.get()

    /** Fence pending speech/audio after pause, resume, stop or session replacement. */
    fun invalidate() {
        epoch.incrementAndGet()
        subscribers.forEach { it.discardStale() }
    }

    fun subscribe(capacity: Int = 8): Subscription = synchronized(ownerLock) {
        require(capacity in 1..16)
        check(!closed && subscribers.size < 2) { "Audioanalyse nicht verfügbar" }
        Subscription(capacity, { epoch.get() }) { subscribers.remove(it) }.also(subscribers::add)
    }

    fun publish(recordingId: String, generation: Long, firstSample: Long, captureTimeNs: Long, sampleRate: Int, bytes: ByteArray, length: Int) {
        if (subscribers.isEmpty() || epoch.get() != generation) return // No subscribers: no copies or microphone change.
        if (length !in 1..minOf(bytes.size, 8192) || length % 2 != 0 || firstSample < 0 || captureTimeNs < 0 || sampleRate <= 0) return
        val immutable = ByteBuffer.wrap(bytes.copyOf(length)).asReadOnlyBuffer().order(ByteOrder.nativeOrder())
        for (subscriber in subscribers) {
            subscriber.offer(PcmBlock(recordingId, generation, firstSample, captureTimeNs, sampleRate, immutable.asReadOnlyBuffer().order(ByteOrder.nativeOrder())))
        }
    }

    override fun close() = synchronized(ownerLock) {
        if (!closed) { closed = true; invalidate(); subscribers.toList().forEach { it.close() } }
    }

    internal class Subscription(private val capacity: Int, private val currentGeneration: () -> Long, private val remove: (Subscription) -> Unit) : AutoCloseable {
        private val lock = ReentrantLock()
        private val queue = ArrayDeque<PcmBlock>()
        private val lost = AtomicLong()
        private var closed = false
        val dropped: Long get() = lost.get()

        internal fun offer(frame: PcmBlock) {
            if (!lock.tryLock()) { lost.incrementAndGet(); return }
            try {
                if (closed || frame.generation != currentGeneration()) return
                while (queue.isNotEmpty() && queue.first().generation != currentGeneration()) queue.removeFirst()
                if (queue.size >= capacity) { lost.incrementAndGet(); return }
                queue.addLast(frame)
            } finally { lock.unlock() }
        }
        internal fun discardStale() {
            if (!lock.tryLock()) return
            try { while (queue.isNotEmpty() && queue.first().generation != currentGeneration()) queue.removeFirst() }
            finally { lock.unlock() }
        }
        fun poll(): PcmBlock? = lock.withLock {
            while (queue.isNotEmpty() && queue.first().generation != currentGeneration()) queue.removeFirst()
            if (queue.isEmpty()) null else queue.removeFirst()
        }
        override fun close() {
            lock.withLock { closed = true; queue.clear() }
            remove(this)
        }
    }
}
