package de.kontrakurs.kontraprompter.cloud

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.File
import java.net.URLClassLoader

class UploadConflictTest {
    // Android's boot classloader contains a mock JSONObject. Exercise real JSON
    // parsing separately, while instrumented tests cover Android's implementation.
    private val jsonClass by lazy {
        val jar = File(requireNotNull(System.getProperty("kontraprompter.test.jsonJar")))
        URLClassLoader(arrayOf(jar.toURI().toURL()), ClassLoader.getPlatformClassLoader()).loadClass("org.json.JSONObject")
    }
    private fun parseInput(input: InputStream?) = uploadConflictCode(input) { text ->
        val obj = jsonClass.getConstructor(String::class.java).newInstance(text)
        jsonClass.getMethod("optString", String::class.java, String::class.java).invoke(obj, "code", "") as String
    }
    private fun parse(text: String) = parseInput(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)))
    @Test fun `only the explicit bounded server tombstone response gets the deletion message`() {
        val code = parse("""{"code":"recording_deleted","error":"Diese Aufnahme wurde gelöscht. Für eine neue Aufnahme ist eine neue Aufnahme-ID erforderlich."}""")
        assertEquals(RECORDING_DELETED, code)
        assertEquals(RECORDING_DELETED, uploadConflictKind(code, false))
        assertEquals(RECORDING_DELETED, uploadConflictKind(code, true))
        assertTrue(RECORDING_DELETED_MESSAGE.contains("Original bleibt lokal"))
        assertTrue(RECORDING_DELETED_MESSAGE.contains("Für dieses Skript"))
        assertTrue(RECORDING_DELETED_MESSAGE.contains("neuer Aufnahme-ID"))
        assertEquals(RECORDING_DELETED, parse(""" { "error" : "Gelöscht \"sicher\"", "code" : "recording_deleted" } """))
    }
    @Test fun `malformed oversized absent and unexpected responses remain generic conflicts`() {
        for (text in listOf("", "{", "[]", """{"code":"recording_deleted"}""", """{'code':'recording_deleted','error':'x'}""", """{"code":"recording_deleted","error":"x"} garbage""", """{"code":"recording_deleted","error":"x","extra":true}""", """{"code":null,"error":"x"}""", """{"code":"recording_deleted","error":"${"a".repeat(4096)}"}""")) {
            assertEquals("conflict", uploadConflictKind(parse(text), false))
        }
        assertNull(parseInput(null))
        assertNull(parseInput(object : InputStream() { override fun read(): Int = throw IOException("broken") }))
        val invalidUtf8 = byteArrayOf(0xc3.toByte(), 0x28)
        assertNull(parseInput(ByteArrayInputStream(invalidUtf8)))
    }
    @Test fun `existing camera conflict meanings are unchanged`() {
        assertEquals("manifest_pending", uploadConflictKind(parse("""{"code":"camera_manifest_pending"}"""), true))
        assertEquals("manifest_mismatch", uploadConflictKind(parse("""{"code":"camera_manifest_mismatch"}"""), true))
        assertEquals("manifest_partial", uploadConflictKind(parse("""{"code":"camera_manifest_partial"}"""), true))
        assertEquals("busy", uploadConflictKind(parse("""{"code":"camera_upload_busy"}"""), true))
        assertEquals("conflict", uploadConflictKind(parse("""{"code":"camera_manifest_pending"}"""), false))
        assertEquals("conflict", uploadConflictKind(parse("""{"error":"old server"}"""), true))
        val oldLenient = """{"code":"camera_manifest_pending","error":"""".toByteArray() + byteArrayOf(0xc3.toByte(), 0x28) + """"}""".toByteArray()
        assertEquals("manifest_pending", uploadConflictKind(parseInput(ByteArrayInputStream(oldLenient)), true))
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
    @Test fun `failed tombstone write is explicitly described as not durable`() {
        assertEquals(RECORDING_DELETED_MESSAGE, deletedMessageAfterWrite(true))
        val failed = deletedMessageAfterWrite(false)
        assertTrue(failed.contains("nicht dauerhaft gespeichert"))
        assertTrue(failed.contains("nach Neustart"))
        assertTrue(failed.contains("Original bleibt lokal"))
    }
    @Test fun `slow response cannot outlive its original session`() {
        val cookie = SessionCookie(); cookie.set("old")
        val body = """{"code":"recording_deleted","error":"gelöscht"}""".toByteArray()
        val source = object : ByteArrayInputStream(body) {
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                cookie.set("new")
                return super.read(bytes, offset, length)
            }
        }
        val code = parseInput(source)
        assertEquals(RECORDING_DELETED, code)
        val error = runCatching { checkDeletedResponseSession(code, cookie.get() == "old") }.exceptionOrNull()
        assertTrue(error is UploadException && error.kind == "auth")
        assertTrue(runCatching { requireUploadSession("old", cookie.get()) }.exceptionOrNull() is UploadException)
    }
    @Test fun `legacy random upload ID stays associated with its original destination`() {
        val legacy = UploadEntry("recording", "project-a", "4f5214c1-f6a3-4482-8b01-b0b17de57e13", 1, "hash", "conflict", null, RECORDING_DELETED)
        assertTrue(deletedUploadBlocked(legacy, "recording", "project-a"))
        assertFalse(deletedUploadBlocked(legacy, "recording", "project-b"))
        assertFalse(deletedUploadBlocked(legacy.copy(conflictReason = null), "recording", "project-a"))
    }
    @Test fun `unsaved deletion is held only for same recording and destination`() {
        val failure = CloudUploadState.Failed("r", "project-a", RECORDING_DELETED, RECORDING_DELETED_NOT_SAVED)
        assertEquals(RECORDING_DELETED_NOT_SAVED, unsavedDeletionMessage(failure, "r", "project-a", "uploading"))
        assertNull(unsavedDeletionMessage(failure, "r", "project-b", "uploading"))
        assertNull(unsavedDeletionMessage(failure, "other", "project-a", "uploading"))
        assertNull(unsavedDeletionMessage(failure, "r", "project-a", "confirmed"))
    }
}
