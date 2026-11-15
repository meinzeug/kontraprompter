package de.kontrakurs.voskfixture

import de.kontrakurs.kontraprompter.video.PcmTee

/** Test-only worker adapter; never created by the Android app or a microphone. */
internal class PcmRecorderBridge(
    private val tee: PcmTee,
    private val subscription: PcmTee.Subscription,
    private val decoder: BoundedPcmDecoder,
    private val recordingId: String,
) : AutoCloseable {
    private val generation = tee.generation
    private val drops = subscription.dropped
    private val worker = Thread.currentThread()
    private var stopped = false
    private fun checkWorker() = check(Thread.currentThread() === worker) { "analysis worker changed" }
    private fun stale() = tee.generation != generation || subscription.dropped != drops

    /** Results remain test data; a real consumer still needs an atomic delivery fence. */
    fun pump(): BoundedPcmDecoder.Result {
        checkWorker()
        if (stopped || stale()) { stopped = true; return BoundedPcmDecoder.Result(BoundedPcmDecoder.Kind.FAILED, null) }
        val block = subscription.poll() ?: return BoundedPcmDecoder.Result(BoundedPcmDecoder.Kind.EMPTY, null)
        val length = block.samples.remaining()
        if (stale() || block.recordingId != recordingId || block.generation != generation || block.sampleRate != 48_000 ||
            length !in 2..8192 || length % 2 != 0) {
            stopped = true; return BoundedPcmDecoder.Result(BoundedPcmDecoder.Kind.FAILED, null)
        }
        val bytes = ByteArray(length); block.samples.get(bytes)
        if (!decoder.offer(bytes, bytes.size, block.firstSample, block.sampleRate)) {
            stopped = true; return BoundedPcmDecoder.Result(BoundedPcmDecoder.Kind.FAILED, null)
        }
        val result = decoder.pump()
        if (stale() || result.kind == BoundedPcmDecoder.Kind.FAILED) {
            stopped = true; return BoundedPcmDecoder.Result(BoundedPcmDecoder.Kind.FAILED, null)
        }
        return result
    }
    override fun close() { checkWorker(); stopped = true; subscription.close(); decoder.close() }
}
