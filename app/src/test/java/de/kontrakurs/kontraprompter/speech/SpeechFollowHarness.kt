package de.kontrakurs.kontraprompter.speech

import de.kontrakurs.kontraprompter.video.PcmBlock

/** Test-only lifecycle adapter: no recognizer, transport, device or application binding. */
internal class SpeechFollowHarness(
    private val recording: String,
    private val scriptRevision: String,
    private val channel: Any,
    script: String,
    private val currentAudioGeneration: () -> Long,
    private val currentDropCount: () -> Long,
) {
    data class ResultTicket(val token: Any, val segment: Long, val generation: Long)
    data class Position(val channel: Any, val recording: String, val scriptRevision: String,
                        val revision: Long, val sequence: Long, val word: Int,
                        val audioGeneration: Long, val dropCount: Long)
    private val cursor = SpokenScriptCursor(spokenWords(script))
    private var token: Any? = null
    private var generation = -1L
    private var drops = -1L
    private var revision = 0L
    private var cursorEpoch = -1L
    private var nextSample: Long? = null
    private var sequence = 0L

    @Synchronized fun begin(anchor: Int): Any {
        check(revision < Long.MAX_VALUE)
        revision++; generation = currentAudioGeneration(); drops = currentDropCount(); cursorEpoch = cursor.begin(anchor)
        nextSample = null; sequence = 0; return Any().also { token = it }
    }
    @Synchronized fun pause() { token = null; cursor.pause(); nextSample = null }
    /** Called on the analysis worker after it polls the bounded tee. */
    @Synchronized fun input(block: PcmBlock, segment: Long): ResultTicket? {
        val current = token ?: return null
        if (generation != currentAudioGeneration() || drops != currentDropCount() || block.recordingId != recording ||
            block.generation != generation || block.sampleRate != 48_000 || segment < 0 ||
            block.samples.remaining() == 0 || block.samples.remaining() % 2 != 0 ||
            (nextSample != null && nextSample != block.firstSample)) {
            pause(); return null
        }
        nextSample = try { Math.addExact(block.firstSample, (block.samples.remaining() / 2).toLong()) }
                     catch (_: ArithmeticException) { pause(); return null }
        return ResultTicket(current, segment, generation)
    }
    /** Final results only; caller rechecks identity at delivery after any intervening suspend. */
    @Synchronized fun result(ticket: ResultTicket, transcript: String, final: Boolean): Position? {
        if (token !== ticket.token || ticket.generation != generation || generation != currentAudioGeneration() || drops != currentDropCount()) return null
        val word = cursor.accept(cursorEpoch, ticket.segment, transcript, final) ?: return null
        if (sequence == Long.MAX_VALUE) { pause(); return null }
        return Position(channel, recording, scriptRevision, revision, ++sequence, word, generation, drops)
    }
}

/** Test-only receiver; it must be called under the real channel/seek ownership fence. */
internal class SpeechPositionHarness(private val channel: Any, private val recording: String,
                                     private val scriptRevision: String,
                                     private val currentAudioGeneration: () -> Long,
                                     private val currentDropCount: () -> Long,
                                     private val activeChannel: () -> Any?) {
    private var revision = 0L
    private var sequence = 0L
    private var position = 0
    @Synchronized fun bind(revision: Long, anchor: Int) { this.revision = revision; sequence = 0; position = anchor }
    @Synchronized fun apply(value: SpeechFollowHarness.Position): Boolean {
        if (activeChannel() !== channel || value.channel !== channel || value.recording != recording || value.scriptRevision != scriptRevision ||
            value.audioGeneration != currentAudioGeneration() || value.dropCount != currentDropCount() ||
            value.revision != revision || value.sequence <= sequence || value.word <= position) return false
        sequence = value.sequence; position = value.word; return true
    }
}
