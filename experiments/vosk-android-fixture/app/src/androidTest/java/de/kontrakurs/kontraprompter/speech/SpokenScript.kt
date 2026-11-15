package de.kontrakurs.kontraprompter.speech

import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

internal data class SpokenWord(val text: String, val start: Int, val end: Int, val normalized: String)
private val WORD = Regex("[\\p{L}\\p{N}]+(?:['’\\-][\\p{L}\\p{N}]+)*")
private val MARKS = Regex("\\p{M}+")
private val NON_WORD = Regex("[^\\p{L}\\p{N}]")

/** UTF-16 offsets match the Android text layout; raw speech is never stored here. */
internal fun spokenWords(text: String): List<SpokenWord> = WORD.findAll(text).map { match ->
    val value = match.value.lowercase(Locale.GERMANY).replace("ß", "ss")
    val normalized = Normalizer.normalize(value, Normalizer.Form.NFKD).replace(MARKS, "").replace(NON_WORD, "")
    SpokenWord(match.value, match.range.first, match.range.last + 1, normalized)
}.toList()

private fun similar(first: String, second: String): Boolean {
    if (first == second) return true
    if (min(first.length, second.length) < 5 || abs(first.length - second.length) > 1) return false
    var i = 0; var j = 0; var changes = 0
    while (i < first.length && j < second.length) {
        if (first[i] == second[j]) { i++; j++; continue }
        if (++changes > 1) return false
        when {
            first.length > second.length -> i++
            second.length > first.length -> j++
            else -> { i++; j++ }
        }
    }
    return changes + (if (i < first.length || j < second.length) 1 else 0) <= 1
}

/** Based on Studio matching, with an additional conservative limit on a single jump. */
internal fun matchSpokenWords(words: List<SpokenWord>, transcript: String, anchor: Int): Int? {
    if (transcript.length > 65_536) return null
    val heard = WORD.findAll(transcript).take(1000).map { match ->
        val value = match.value.lowercase(Locale.GERMANY).replace("ß", "ss")
        Normalizer.normalize(value, Normalizer.Form.NFKD).replace(MARKS, "").replace(NON_WORD, "")
    }.toList()
    if (heard.size < 2 || words.isEmpty()) return null
    val start = anchor.coerceIn(0, words.size)
    val limit = min(words.size, start + min(1200, max(65, heard.size * 2 + 16)))
    var next = start; var matched = 0; var last = -1
    for (word in heard) {
        val end = min(limit, next + if (matched != 0) 6 else 16)
        val index = (next until end).firstOrNull { similar(word, words[it].normalized) } ?: continue
        matched++; last = index; next = index + 1
    }
    val maxAdvance = min(80, max(8, heard.size * 2 + 4))
    return if (matched >= 2 && matched.toDouble() / heard.size >= 0.34 && last + 1 - start <= maxAdvance) last + 1 else null
}

/**
 * Recognition is supplied externally from the camera's verified shared audio.
 * Epochs fence delayed callbacks after pause, script change or manual seek.
 * This class does not capture microphones or start a speech-recognition service.
 */
internal class SpokenScriptCursor(private val words: List<SpokenWord>) {
    private var epoch = 0L
    private var active = false
    private var confirmed = 0
    private var visible = 0
    private var currentSegment = -1L
    private var segmentAnchor = 0
    private var segmentFinal = false

    @Synchronized fun begin(anchor: Int): Long {
        check(epoch < Long.MAX_VALUE)
        epoch++; active = true; confirmed = anchor.coerceIn(0, words.size); visible = confirmed
        currentSegment = -1; segmentFinal = false; segmentAnchor = confirmed
        return epoch
    }
    @Synchronized fun pause() { active = false; if (epoch < Long.MAX_VALUE) epoch++ }
    @Synchronized fun position(): Int = visible

    /** Returns a new word position only when a valid recognition result advances it. */
    @Synchronized fun accept(generation: Long, segment: Long, transcript: String, final: Boolean): Int? {
        if (!active || generation != epoch || segment < 0 || segment < currentSegment) return null
        if (segment > currentSegment) {
            if (currentSegment >= 0 && !segmentFinal) return null
            currentSegment = segment; segmentAnchor = confirmed; segmentFinal = false
        }
        if (segmentFinal) return null
        // Interim hypotheses can be revised dramatically. Never scroll or commit them.
        if (!final) return null
        val found = matchSpokenWords(words, transcript, segmentAnchor)
        val before = visible
        if (found != null) visible = max(visible, found)
        confirmed = max(confirmed, visible); segmentFinal = true
        return visible.takeIf { it > before }
    }
}
