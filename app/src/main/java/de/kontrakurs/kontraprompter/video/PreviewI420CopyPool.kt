package de.kontrakurs.kontraprompter.video

import org.webrtc.JavaI420Buffer
import org.webrtc.VideoFrame
import java.nio.ByteBuffer

/**
 * Experimental, unconnected copy pool for *already CPU-accessible* I420 frames.
 * Never attach this to the shared camera track as a means of converting textures:
 * obtaining an I420 frame from the camera may itself block or hold its sole
 * texture. That upstream problem and the native encoder/network drain remain open.
 *
 * The caller must keep the borrowed input valid until [copy] returns. Input
 * getters and the byte copy execute synchronously on the caller's thread and
 * may themselves block. [close] does not wait for or cancel an admitted read.
 * Output has independent direct storage and retains a bounded slot until its final WebRTC release. A blocked
 * consumer can exhaust the slots, in which case later copies are dropped. It
 * cannot retain the input through an output frame. Existing output references are
 * not revoked by [close]. A copy that passed the last closed check can return
 * after close; a separate frame barrier must guard any eventual delivery.
 */
internal class PreviewI420CopyPool(
    private val maxWidth: Int = 640,
    private val maxHeight: Int = 480,
    slotCount: Int = 2,
) : AutoCloseable {
    private class Slot(val bytes: ByteBuffer, var busy: Boolean = false)
    private val lock = Any()
    private var closed = false
    private val slots: List<Slot>

    init {
        require(maxWidth in 1..640 && maxHeight in 1..480 && slotCount in 1..2)
        val chromaWidth = (maxWidth + 1) / 2
        val chromaHeight = (maxHeight + 1) / 2
        val capacity = maxWidth * maxHeight + 2 * chromaWidth * chromaHeight
        slots = List(slotCount) { Slot(ByteBuffer.allocateDirect(capacity)) }
    }

    /** Returns an owned frame or null for unsupported, invalid, closed or busy input. */
    fun copy(frame: VideoFrame): VideoFrame? {
        // Deliberately do not call Buffer.toI420(): texture conversion is not
        // proven nonblocking or independent of the recorder's camera buffer.
        val input = frame.buffer as? VideoFrame.I420Buffer ?: return null
        val slot = synchronized(lock) {
            if (closed) null else slots.firstOrNull { !it.busy }?.also { it.busy = true }
        } ?: return null

        var wrapped: JavaI420Buffer? = null
        var handedOff = false
        try {
            // No external/native getter is called while holding the pool lock.
            // The same fixed byte budget accommodates landscape and portrait.
            val width = input.width
            val height = input.height
            val fitsLandscape = width in 1..maxWidth && height in 1..maxHeight
            val fitsPortrait = width in 1..maxHeight && height in 1..maxWidth
            if (!fitsLandscape && !fitsPortrait) return null
            val cw = (width + 1) / 2
            val ch = (height + 1) / 2
            val ySize = width * height
            val cSize = cw * ch
            val y = slice(slot.bytes, 0, ySize)
            val u = slice(slot.bytes, ySize, cSize)
            val v = slice(slot.bytes, ySize + cSize, cSize)
            copyPlane(input.dataY, input.strideY, width, height, y)
            copyPlane(input.dataU, input.strideU, cw, ch, u)
            copyPlane(input.dataV, input.strideV, cw, ch, v)
            y.clear(); u.clear(); v.clear()
            wrapped = JavaI420Buffer.wrap(width, height, y, width, u, cw, v, cw) { release(slot) }
            val output = VideoFrame(wrapped, frame.rotation, frame.timestampNs)
            // If close won before this check, do not publish. A copy that has
            // already passed this check still needs the separate delivery gate.
            if (synchronized(lock) { closed }) return null
            handedOff = true
            return output
        } catch (_: RuntimeException) {
            return null
        } finally {
            if (!handedOff) {
                if (wrapped != null) wrapped.release() else release(slot)
            }
        }
    }

    private fun release(slot: Slot) = synchronized(lock) { slot.busy = false }

    override fun close() = synchronized(lock) { closed = true }

    private fun slice(bytes: ByteBuffer, start: Int, length: Int): ByteBuffer =
        bytes.duplicate().apply { position(start); limit(start + length) }.slice()

    private fun copyPlane(source: ByteBuffer, stride: Int, width: Int, height: Int, target: ByteBuffer) {
        require(stride >= width)
        val input = source.duplicate()
        val start = input.position()
        val required = (height - 1L) * stride + width
        require(required <= input.remaining().toLong())
        for (row in 0 until height) {
            val offset = start + row * stride
            val line = input.duplicate().apply { position(offset); limit(offset + width) }
            target.put(line)
        }
    }
}
