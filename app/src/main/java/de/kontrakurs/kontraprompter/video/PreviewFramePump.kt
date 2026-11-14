package de.kontrakurs.kontraprompter.video

import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Bounded asynchronous delivery for a future browser-only frame source.
 *
 * This is not connected to the live preview. It must not be attached directly
 * to the shared camera track: retaining a texture can exhaust its native
 * buffer pool and stall the recorder or phone video even though the downstream
 * runs on a different thread. A future integration needs independently owned
 * copies and must prove their lifecycle on real devices first.
 *
 * At most one frame runs and one frame waits, and excess frames are dropped.
 * Every accepted frame is retained until its task returns. A stalled downstream
 * cannot accumulate unbounded frames. Native retain/release calls are still
 * synchronous on the thread that invokes them and may themselves block.
 *
 * Revocation closes admissions and the underlying barrier without waiting for
 * an already admitted downstream call. Completion additionally waits for every
 * reference owned by this pump to be released. It says nothing about references
 * retained independently by the downstream, native encoder or network buffers.
 * Never wait for completion from the downstream callback.
 */
internal class PreviewFramePump(downstream: VideoSink) : VideoSink {
    private val lock = Any()
    private val barrier = PreviewFrameBarrier(downstream)
    private val worker = ThreadPoolExecutor(
        1, 1, 0, TimeUnit.SECONDS, ArrayBlockingQueue(1),
        { task -> Thread(task, "preview-frame-pump").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )
    private val drained = CountDownLatch(1)
    private var revoked = false
    private var accepted = 0
    private var releaseUnknown = false
    private val completion = object : PreviewDrain {
        override val isDrained: Boolean get() = drained.count == 0L
        override fun await(timeout: Long, unit: TimeUnit): Boolean = drained.await(timeout, unit)
    }

    override fun onFrame(frame: VideoFrame) {
        // Count the callback before entering native retain. If retain stalls,
        // revoke can still close admissions, but must not report completion.
        val admitted = synchronized(lock) {
            if (revoked || accepted >= 2) false else { accepted++; true }
        }
        if (!admitted) return
        var retained = false
        var submitted = false
        try {
            frame.retain()
            retained = true
            synchronized(lock) {
                if (!revoked) {
                    try {
                        worker.execute {
                            try { barrier.onFrame(frame) }
                            catch (_: Throwable) { revoke() }
                            finally { finish(runCatching { frame.release() }.isSuccess) }
                        }
                        submitted = true
                    } catch (_: RuntimeException) {
                        // Executor rejected; the caller releases its own retain.
                    }
                }
            }
        } catch (_: Throwable) {
            // A failing native retain may have partially succeeded. Fail closed.
            revoke()
        } finally {
            if (!submitted) finish(retained && runCatching { frame.release() }.isSuccess)
        }
    }

    private fun finish(released: Boolean) = synchronized(lock) {
        if (!released) {
            releaseUnknown = true
            if (!revoked) { revoked = true; barrier.revoke(); worker.shutdown() }
        }
        accepted--
        if (revoked && accepted == 0 && !releaseUnknown) drained.countDown()
    }

    /** Closes admission synchronously; the read-only signal reports release. */
    fun revoke(): PreviewDrain = synchronized(lock) {
        if (!revoked) {
            revoked = true
            barrier.revoke()
            worker.shutdown()
            if (accepted == 0 && !releaseUnknown) drained.countDown()
        }
        completion
    }
}
