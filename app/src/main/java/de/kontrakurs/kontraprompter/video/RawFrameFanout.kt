package de.kontrakurs.kontraprompter.video

/**
 * One recorder sees borrowed camera frames before WebRTC adapts the track.
 * Delivery is synchronous; callbacks must not keep a frame without retaining it.
 * Detach fences in-flight callbacks without holding the monitor while they run.
 */
internal interface RawFrameSource<T> {
    fun attach(owner: Any, onFrame: (T) -> Unit, onFailure: () -> Unit): Boolean
    fun detach(owner: Any)
}

internal class RawFrameFanout<T> : RawFrameSource<T> {
    private class Slot<T>(val owner: Any, val onFrame: (T) -> Unit, val onFailure: () -> Unit) { var inFlight = 0 }
    private val monitor = Object()
    private val insideCallback = ThreadLocal<Boolean>()
    private var current: Slot<T>? = null
    private var draining: Slot<T>? = null
    private var closed = false

    override fun attach(owner: Any, onFrame: (T) -> Unit, onFailure: () -> Unit): Boolean = synchronized(monitor) {
        if (closed || draining != null || current != null) false
        else { current = Slot(owner, onFrame, onFailure); true }
    }

    override fun detach(owner: Any) {
        check(insideCallback.get() != true) { "A raw camera callback cannot detach itself" }
        var interrupted = false
        synchronized(monitor) {
            val slot = when {
                current?.owner === owner -> current.also { current = null; draining = it }
                draining?.owner === owner -> draining
                else -> null
            } ?: return
            while (slot.inFlight != 0) {
                try { monitor.wait() } catch (_: InterruptedException) { interrupted = true }
            }
            if (draining === slot) draining = null
            monitor.notifyAll()
        }
        if (interrupted) Thread.currentThread().interrupt()
    }

    /** Does not retain the borrowed frame; the consumer must retain for async work. */
    fun deliver(frame: T) {
        val slot = synchronized(monitor) { current?.also { it.inFlight++ } } ?: return
        try {
            insideCallback.set(true)
            try { slot.onFrame(frame) } catch (_: Exception) { runCatching { slot.onFailure() } }
        } finally {
            insideCallback.remove()
            synchronized(monitor) { slot.inFlight--; if (slot.inFlight == 0) monitor.notifyAll() }
        }
    }

    /** Recorder failures never swallow a frame from the existing WebRTC source. */
    fun deliverThen(frame: T, upstream: (T) -> Unit) {
        try { deliver(frame) } finally { upstream(frame) }
    }

    fun close() {
        check(insideCallback.get() != true) { "A raw camera callback cannot close its source" }
        val (slot, notifyFailure) = synchronized(monitor) {
            if (closed) draining to false
            else {
                closed = true
                val active = current
                if (active != null) {
                    current = null
                    draining = active
                    active to true
                } else draining to false
            }
        }
        if (slot != null) {
            if (notifyFailure) runCatching { slot.onFailure() }
            detach(slot.owner)
        }
    }
}
