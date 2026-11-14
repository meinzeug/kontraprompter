package de.kontrakurs.kontraprompter

import org.junit.Assert.*
import org.junit.Test

class GenerationNoticeTest {
    @Test fun `missing warnings stay unknown rather than implying an approval`() {
        val result = parseGenerationNotice(false, null)
        assertEquals(GenerationNoticeStatus.UNKNOWN, result.status)
        assertTrue(result.needsAttention())
        assertTrue(result.summary().contains("Keine Angaben"))
    }

    @Test fun `valid warnings remain historical text and a valid empty list stays distinct`() {
        val result = parseGenerationNotice(true, listOf("  Möglicherweise anderer Kanal.  ", "Kanal entfernt."))
        assertEquals(GenerationNoticeStatus.PROVIDED, result.status)
        assertEquals(listOf("Möglicherweise anderer Kanal.", "Kanal entfernt."), result.messages)
        assertTrue(result.needsAttention())
        val empty = parseGenerationNotice(true, emptyList<String>())
        assertEquals(GenerationNoticeStatus.PROVIDED, empty.status)
        assertFalse(empty.needsAttention())
    }

    @Test fun `malformed values never become a successful empty warning result`() {
        assertEquals(GenerationNoticeStatus.INCOMPLETE, parseGenerationNotice(true, null).status)
        assertEquals(GenerationNoticeStatus.INCOMPLETE, parseGenerationNotice(true, "safe").status)
        val result = parseGenerationNotice(true, listOf("Wichtiger Hinweis", 42, null, "  "))
        assertEquals(GenerationNoticeStatus.INCOMPLETE, result.status)
        assertEquals(listOf("Wichtiger Hinweis"), result.messages)
        assertTrue(result.summary().contains("unvollständig"))
    }

    @Test fun `oversized and control-bearing metadata stays visibly incomplete`() {
        val result = parseGenerationNotice(true, listOf("A".repeat(1001), "B\u0000C") + List(10) { "Hinweis $it" })
        assertEquals(GenerationNoticeStatus.INCOMPLETE, result.status)
        assertEquals(10, result.messages.size)
        assertEquals(1000, result.messages.first().length)
        assertEquals("B C", result.messages[1])
    }

    @Test fun `editing retains historical warnings and marks them incomplete`() {
        val notice = parseGenerationNotice(true, listOf("Kanal prüfen"))
        val local = Script(title = "Test", text = "Original", generationNotice = notice)
        val edited = preserveNoticeOnEdit(local, local.copy(text = "Bearbeitet"))
        assertEquals(GenerationNoticeStatus.INCOMPLETE, edited.generationNotice?.status)
        assertEquals(listOf("Kanal prüfen"), edited.generationNotice?.messages)
        assertEquals(notice, preserveNoticeOnEdit(local, local.copy()).generationNotice)
        val empty = Script(text = "Original", generationNotice = parseGenerationNotice(true, emptyList<String>()))
        assertEquals(GenerationNoticeStatus.INCOMPLETE, preserveNoticeOnEdit(empty, empty.copy(text = "Importiert")).generationNotice?.status)
        assertEquals(GenerationNoticeStatus.INCOMPLETE, preserveNoticeOnEdit(empty, empty.copy(title = "Neuer Titel")).generationNotice?.status)
        assertNull(Script().generationNotice)
    }

    @Test fun `project text is never coerced from non-string model data`() {
        assertEquals("Skript", requireProjectString("Skript"))
        assertTrue(runCatching { requireProjectString(null) }.isFailure)
        assertTrue(runCatching { requireProjectString(42) }.isFailure)
        assertTrue(runCatching { requireProjectString(listOf("Skript")) }.isFailure)
    }
}
