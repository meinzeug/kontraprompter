package de.kontrakurs.kontraprompter.speech

import org.junit.Assert.*
import org.junit.Test

class SpokenScriptTest {
    @Test fun germanNormalizationAndUtf16Offsets() {
        val words = spokenWords("Ärger auf der Straße – läuft's!")
        assertEquals(listOf("arger", "auf", "der", "strasse", "laufts"), words.map { it.normalized })
        assertEquals("Straße", "Ärger auf der Straße – läuft's!".substring(words[3].start, words[3].end))
    }
    @Test fun matchNeedsMultipleWordsAndIsForwardOnly() {
        val words = spokenWords("Leute das ist heute eine wichtige Nachricht für alle Bürger")
        assertNull(matchSpokenWords(words, "Leute", 0))
        assertEquals(4, matchSpokenWords(words, "das ist heute", 0))
        assertNull(matchSpokenWords(words, "Leute das", 5))
    }
    @Test fun pausesAndDelayedOldCallbacksNeverMoveTheCursor() {
        val cursor = SpokenScriptCursor(spokenWords("Eins zwei drei vier fünf sechs"))
        val old = cursor.begin(0)
        assertEquals(2, cursor.accept(old, 1, "Eins zwei", true))
        cursor.pause()
        assertNull(cursor.accept(old, 2, "drei vier", true))
        assertEquals(2, cursor.position())
        val next = cursor.begin(2)
        assertNull(cursor.accept(old, 2, "drei vier", true))
        assertEquals(4, cursor.accept(next, 1, "drei vier", true))
    }
    @Test fun unfinishedOrFinalizedSegmentsCannotReplay() {
        val cursor = SpokenScriptCursor(spokenWords("Eins zwei drei vier fünf sechs"))
        val epoch = cursor.begin(0)
        assertNull(cursor.accept(epoch, 4, "eins zwei", false))
        assertNull(cursor.accept(epoch, 5, "drei vier", true))
        assertEquals(2, cursor.accept(epoch, 4, "eins zwei", true))
        assertNull(cursor.accept(epoch, 4, "eins zwei drei", false))
        assertEquals(4, cursor.accept(epoch, 5, "drei vier", true))
    }
    @Test fun correctedFinalDoesNotCommitAnErroneousInterimJump() {
        val cursor = SpokenScriptCursor(spokenWords("eins zwei drei vier fünf sechs"))
        val epoch = cursor.begin(0)
        assertNull(cursor.accept(epoch, 1, "fünf sechs", false))
        assertEquals(0, cursor.position())
        assertEquals(2, cursor.accept(epoch, 1, "eins zwei", true))
        assertEquals(2, cursor.position())
        assertNull(matchSpokenWords(spokenWords("eins zwei"), "a".repeat(65_537), 0))
        val far = spokenWords("alpha beta gamma delta epsilon zeta eta theta iota kappa eins zwei")
        assertNull(matchSpokenWords(far, "eins zwei", 0))
    }

}
