package de.kontrakurs.kontraprompter.video

import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Read-only completion signal; callers cannot mark a blocked delivery as drained. */
internal interface PreviewDrain {
    val isDrained: Boolean
    fun await(timeout: Long, unit: TimeUnit): Boolean
}

/**
 * One-shot barrier for a future, separate browser-only frame path. This class is not
 * connected to the current preview: that peer still sends the shared camera track.
 *
 * Revocation prevents new admissions without waiting for an already admitted sink
 * call. An already admitted call may enter the downstream even after revoke returns.
 * The returned signal completes only when every admitted call has returned.
 * It confirms neither native encoder cleanup nor delivery of buffered network data.
 * Frames are borrowed and forwarded synchronously; the source must keep each frame
 * alive until this call returns. A downstream that uses a frame asynchronously must
 * retain and release it itself; the signal does not confirm release of that reference.
 * Never wait for the signal from an admitted downstream call: it must return first.
 */
internal class PreviewFrameBarrier(private val downstream: VideoSink) : VideoSink {
    private val lock = Any()
    private var revoked = false
    private var admitted = 0
    private val drained = CountDownLatch(1)
    private val completion = object : PreviewDrain {
        override val isDrained: Boolean get() = drained.count == 0L
        override fun await(timeout: Long, unit: TimeUnit): Boolean = drained.await(timeout, unit)
    }

    override fun onFrame(frame: VideoFrame) {
        synchronized(lock) {
            if (revoked) return
            admitted++
        }
        try {
            downstream.onFrame(frame)
        } finally {
            synchronized(lock) {
                admitted--
                if (revoked && admitted == 0) drained.countDown()
            }
        }
    }

    /** Idempotently closes admissions; never waits for a blocked downstream. */
    fun revoke(): PreviewDrain = synchronized(lock) {
        revoked = true
        if (admitted == 0) drained.countDown()
        completion
    }
}
