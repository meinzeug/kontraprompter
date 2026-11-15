package de.kontrakurs.kontraprompter.cloud

import android.content.Context
import android.content.ContextWrapper
import android.test.InstrumentationTestCase
import android.util.AtomicFile
import de.kontrakurs.kontraprompter.video.SavedRecording
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Separate .uploadfixture package and synthetic files only; never uses real recordings. */
class UploadDeletedJournalTest : InstrumentationTestCase() {
    private class FixtureContext(base: Context, private val dir: File) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = dir
    }
    private fun id() = UUID.randomUUID().toString()
    private inline fun inFixture(test: (Context, File, SavedRecording, String) -> Unit) {
        val target = instrumentation.targetContext
        check(target.packageName == "de.kontrakurs.kontraprompter.uploadfixture")
        val dir = File(target.cacheDir, "upload-fixture-${id()}"); check(dir.mkdir())
        try {
            val bytes = "synthetic video fixture".toByteArray(); val file = File(dir, "sample.mp4"); file.writeBytes(bytes)
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
            val recording = SavedRecording(id(), file, bytes.size.toLong(), 1000, 1, hash, false, "fixture")
            test(FixtureContext(target, dir), dir, recording, id())
        } finally { check(dir.deleteRecursively()) }
    }
    private fun legacy(context: Context, dir: File, recording: SavedRecording, project: String, phase: String = "uncertain", reason: Any? = null): String {
        val random = id(); assertFalse(random == uploadIdFor(recording.id, project))
        val folder = File(dir, "camera-upload-journal"); check(folder.mkdir())
        val file = File(folder, "${recording.id}_${project}.json")
        val payload = JSONObject().put("version", 1).put("recordingId", recording.id).put("projectId", project)
            .put("uploadId", random).put("sizeBytes", recording.bytes).put("sha256", recording.sha256).put("phase", phase)
        if (reason != null) payload.put("conflictReason", reason)
        val atomic = AtomicFile(file); val out = atomic.startWrite()
        try { out.write(payload.toString().toByteArray()); out.fd.sync(); atomic.finishWrite(out) }
        catch (error: Exception) { atomic.failWrite(out); throw error }
        return random
    }
    fun testLegacyRandomIdRemainsAndDeletedReasonSurvivesReopenPerProject() = inFixture { context, dir, recording, project ->
        val oldId = legacy(context, dir, recording, project)
        val old = checkNotNull(UploadJournal.get(context, recording, project)); assertEquals(oldId, old.uploadId); assertNull(old.conflictReason)
        val started = UploadJournal.begin(context, recording, project); assertEquals(oldId, started.uploadId)
        UploadJournal.write(context, started.copy(phase = "conflict", conflictReason = RECORDING_DELETED))
        val reopened = checkNotNull(UploadJournal.get(context, recording, project))
        assertEquals(oldId, reopened.uploadId); assertEquals(RECORDING_DELETED, reopened.conflictReason)
        try { UploadJournal.begin(context, recording, project); fail("deleted target unexpectedly retried") }
        catch (expected: UploadException) { assertEquals(RECORDING_DELETED, expected.kind) }
        val alternate = UploadJournal.begin(context, recording, id())
        assertFalse(alternate.uploadId == oldId); assertNull(alternate.conflictReason)
    }
    fun testUnknownOptionalReasonFallsBackAndBrokenJsonStaysBlocked() = inFixture { context, dir, recording, project ->
        legacy(context, dir, recording, project, phase = "conflict", reason = 17)
        val old = checkNotNull(UploadJournal.get(context, recording, project))
        assertEquals("conflict", old.phase); assertNull(old.conflictReason)
        val file = File(File(dir, "camera-upload-journal"), "${recording.id}_${project}.json")
        val atomic = AtomicFile(file); val out = atomic.startWrite()
        try { out.write("{".toByteArray()); out.fd.sync(); atomic.finishWrite(out) } catch (error: Exception) { atomic.failWrite(out); throw error }
        assertTrue(runCatching { UploadJournal.get(context, recording, project) }.isFailure)
    }
    fun testAtomicWriteFailureIsReportedAndOriginalJournalRemainsReadable() = inFixture { context, dir, recording, project ->
        val started = UploadJournal.begin(context, recording, project)
        val folder = File(dir, "camera-upload-journal"); assertTrue(folder.setWritable(false, false))
        try {
            assertTrue(runCatching { UploadJournal.write(context, started.copy(phase = "conflict", conflictReason = RECORDING_DELETED)) }.isFailure)
            assertTrue(deletedMessageAfterWrite(false).contains("nicht dauerhaft gespeichert"))
        } finally { assertTrue(folder.setWritable(true, true)) }
        val reopened = checkNotNull(UploadJournal.get(context, recording, project))
        assertTrue(reopened.phase == "uploading" || (reopened.phase == "conflict" && reopened.conflictReason == RECORDING_DELETED))
        assertEquals(started.uploadId, reopened.uploadId)
    }
    fun testRealAndroidJsonHandlesValidAndMalformedServerResponses() {
        fun parse(s: String) = uploadConflictCode(ByteArrayInputStream(s.toByteArray(Charsets.UTF_8)))
        assertEquals(RECORDING_DELETED, parse("""{"code":"recording_deleted","error":"gelöscht"}"""))
        assertEquals("conflict", uploadConflictKind(parse("""{"code":"recording_deleted","error":"x"}garbage"""), false))
        assertEquals("manifest_pending", uploadConflictKind(parse("""{"code":"camera_manifest_pending"}"""), true))
    }
}
