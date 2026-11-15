package de.kontrakurs.voskfixture

import de.kontrakurs.kontraprompter.speech.SpokenScriptCursor
import de.kontrakurs.kontraprompter.speech.spokenWords
import de.kontrakurs.kontraprompter.video.PcmTee
import de.kontrakurs.kontraprompter.video.SpeechLocalDeliveryFence
import java.util.concurrent.atomic.AtomicBoolean

/** Instrumentation-only bridge. It neither owns a microphone nor sends network messages. */
internal class LocalSpeechSession(
    private val tee: PcmTee,
    private val decoder: BoundedPcmDecoder,
    private val channel: Any,
    private val recording: String,
    private val scriptRevision: String,
    script: String,
    anchor: Int,
    private val finalText: (String) -> String?,
    capacity: Int = 8,
) : AutoCloseable {
    private val worker = Thread.currentThread()
    private val words = spokenWords(script)
    private val cursor = SpokenScriptCursor(words)
    private val cursorEpoch = cursor.begin(anchor)
    private val generation = tee.generation
    private val fence = SpeechLocalDeliveryFence(channel, recording, scriptRevision, generation, words.size, anchor)
    // A new queue and decoder are required for each seek, pause or process restart.
    private val subscription = tee.subscribe(capacity, fence)
    private val stopped = AtomicBoolean(false)
    private var segment = 0L
    private var sequence = 0L

    /** May be called on another thread; invalidates before a pending decoder result can commit. */
    fun invalidate() { fence.invalidate(); stopped.set(true) }
    fun positionOrNull(): Int? = fence.positionOrNull()

    /** Analysis worker only. The caller must not run it on the recording or UI thread. */
    fun pump(): Boolean {
        check(Thread.currentThread() === worker)
        if (stopped.get() || !fence.matchesGeneration(generation)) return false
        val block = subscription.poll() ?: return fence.matchesGeneration(generation) && !stopped.get()
        if (!subscription.isCurrent(block) || block.recordingId != recording || block.generation != generation ||
            block.sampleRate != 48_000 || block.samples.remaining() !in 2..8192 || block.samples.remaining() % 2 != 0) {
            invalidate(); return false
        }
        val bytes = ByteArray(block.samples.remaining()); block.samples.get(bytes)
        if (!decoder.offer(bytes, bytes.size, block.firstSample, block.sampleRate)) { invalidate(); return false }
        val result = decoder.pump()
        if (!subscription.isCurrent(block) || stopped.get() || result.kind == BoundedPcmDecoder.Kind.FAILED) {
            invalidate(); return false
        }
        if (result.kind == BoundedPcmDecoder.Kind.FINAL) {
            if (segment == Long.MAX_VALUE || sequence == Long.MAX_VALUE) { invalidate(); return false }
            val text = try { finalText(result.json ?: return fail()) } catch (_: RuntimeException) { return fail() }
                ?: return fail()
            val word = cursor.accept(cursorEpoch, segment++, text, true)
            if (word != null) fence.apply(channel, recording, scriptRevision, generation, ++sequence, word)
        }
        return fence.matchesGeneration(generation) && !stopped.get()
    }
    private fun fail(): Boolean { invalidate(); return false }

    override fun close() {
        check(Thread.currentThread() === worker)
        invalidate(); subscription.close(); decoder.close()
    }
}
