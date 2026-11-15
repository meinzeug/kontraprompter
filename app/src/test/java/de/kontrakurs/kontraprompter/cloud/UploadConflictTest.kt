package de.kontrakurs.kontraprompter.cloud

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

class UploadConflictTest {
    private fun parse(text: String) = uploadConflictCode(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)))
    @Test fun `only the explicit bounded server tombstone response gets the deletion message`() {
        val code = parse("""{"code":"recording_deleted","error":"Diese Aufnahme wurde gelöscht. Für eine neue Aufnahme ist eine neue Aufnahme-ID erforderlich."}""")
        assertEquals(RECORDING_DELETED, code)
        assertEquals(RECORDING_DELETED, uploadConflictKind(code, false))
        assertEquals(RECORDING_DELETED, uploadConflictKind(code, true))
        assertTrue(RECORDING_DELETED_MESSAGE.contains("Original bleibt lokal"))
        assertTrue(RECORDING_DELETED_MESSAGE.contains("neue Aufnahme-ID"))
        assertEquals(RECORDING_DELETED, parse(""" { "error" : "Gelöscht \"sicher\"", "code" : "recording_deleted" } """))
    }
    @Test fun `malformed oversized absent and unexpected responses remain generic conflicts`() {
        for (text in listOf("", "{", "[]", """{"code":"recording_deleted"}""", """{'code':'recording_deleted','error':'x'}""", """{"code":"recording_deleted","error":"x"} garbage""", """{"code":"recording_deleted","error":"x","extra":true}""", """{"code":null,"error":"x"}""", """{"code":"recording_deleted","error":"${"a".repeat(4096)}"}""")) {
            assertEquals("conflict", uploadConflictKind(parse(text), false))
        }
        assertNull(uploadConflictCode(null))
        assertNull(uploadConflictCode(object : InputStream() { override fun read(): Int = throw IOException("broken") }))
        val invalidUtf8 = byteArrayOf(0xc3.toByte(), 0x28)
        assertNull(uploadConflictCode(ByteArrayInputStream(invalidUtf8)))
    }
    @Test fun `existing camera conflict meanings are unchanged`() {
        assertEquals("manifest_pending", uploadConflictKind(parse("""{"code":"camera_manifest_pending"}"""), true))
        assertEquals("manifest_mismatch", uploadConflictKind(parse("""{"code":"camera_manifest_mismatch"}"""), true))
        assertEquals("manifest_partial", uploadConflictKind(parse("""{"code":"camera_manifest_partial"}"""), true))
        assertEquals("busy", uploadConflictKind(parse("""{"code":"camera_upload_busy"}"""), true))
        assertEquals("conflict", uploadConflictKind(parse("""{"code":"camera_manifest_pending"}"""), false))
        assertEquals("conflict", uploadConflictKind(parse("""{"error":"old server"}"""), true))
        val oldLenient = """{"code":"camera_manifest_pending","error":"""".toByteArray() + byteArrayOf(0xc3.toByte(), 0x28) + """"}""".toByteArray()
        assertEquals("manifest_pending", uploadConflictKind(uploadConflictCode(ByteArrayInputStream(oldLenient)), true))
    }
    @Test fun `reason is optional backward compatible and scoped to the exact recording and project`() {
        val entry = UploadEntry("recording", "project-a", "upload", 1, "hash", "conflict", null, RECORDING_DELETED)
        assertTrue(deletedUploadBlocked(entry, "recording", "project-a"))
        assertFalse(deletedUploadBlocked(entry, "recording", "project-b"))
        assertFalse(deletedUploadBlocked(entry, "other", "project-a"))
        assertFalse(deletedUploadBlocked(null, "recording", "project-a"))
        assertFalse(deletedUploadBlocked(entry.copy(conflictReason = null), "recording", "project-a"))
        assertFalse(deletedUploadBlocked(entry.copy(phase = "uncertain"), "recording", "project-a"))
        assertNull(uploadConflictReason(null, "conflict"))
        assertNull(uploadConflictReason(123, "conflict"))
        assertNull(uploadConflictReason("unexpected", "conflict"))
        assertNull(uploadConflictReason(RECORDING_DELETED, "confirmed"))
        assertEquals(RECORDING_DELETED, uploadConflictReason(RECORDING_DELETED, "conflict"))
        assertEquals("conflict", failedUploadPhase(RECORDING_DELETED))
        assertTrue(blocksManualContinuation(RECORDING_DELETED))
        assertFalse(blocksManualContinuation("busy"))
    }
}
