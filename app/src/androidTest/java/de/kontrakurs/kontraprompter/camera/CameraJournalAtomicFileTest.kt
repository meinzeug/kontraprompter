package de.kontrakurs.kontraprompter.camera

import android.content.Context
import android.content.ContextWrapper
import android.test.InstrumentationTestCase
import android.util.AtomicFile
import java.io.File
import java.util.UUID

/** Only the separate .journalfixture test application and random cache files are used. */
class CameraJournalAtomicFileTest : InstrumentationTestCase() {
    private class FixtureContext(base: Context, private val testFiles: File) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = testFiles
    }
    private fun freshId() = UUID.randomUUID().toString()
    private fun newJournal(context: Context, id: String): RemoteCameraJournal {
        val ctor = RemoteCameraJournal::class.java.getDeclaredConstructor(Context::class.java, String::class.java)
        ctor.isAccessible = true
        return ctor.newInstance(context, id)
    }
    private inline fun inFixture(test: (Context, File, String) -> Unit) {
        val target = instrumentation.targetContext
        check(target.packageName.endsWith(".journalfixture")) { "Requires separate test application" }
        val files = File(target.cacheDir, "journal-fixture-${freshId()}")
        check(files.mkdir())
        try { test(FixtureContext(target, files), files, freshId()) }
        finally { check(files.deleteRecursively()) }
    }
    private fun initialized(context: Context, files: File, id: String): RemoteCameraJournal {
        val dir = File(files, "remote-camera"); check(dir.mkdir())
        RemoteCameraJournal.ensureInitialized(dir, id, mayCreate = true, mayMigrate = false)
        return newJournal(context, id)
    }
    fun testReopenPendingAdmissionAsUncertainWithoutReexecution() = inFixture { context, files, device ->
        val journal = initialized(context, files, device)
        val command = CameraCommand(freshId(), "record_start", freshId(), freshId())
        val decision = journal.decide(command, expired = false, canStart = true)
        assertEquals("admitting", decision.result)
        val reopened = newJournal(context, device)
        val stored = reopened.snapshot().decisions.single()
        assertEquals("uncertain", stored.result)
        assertTrue(stored.pendingAck)
        assertNull(reopened.decide(command, expired = false, canStart = true).action)
    }
    fun testInterruptedAtomicWritePreservesPreviouslyDurableDecision() = inFixture { context, files, device ->
        val journal = initialized(context, files, device)
        val command = CameraCommand(freshId(), "record_start", freshId(), freshId())
        journal.decide(command, expired = false, canStart = true)
        val target = File(File(files, "remote-camera"), cameraJournalFileName(device))
        val pending = AtomicFile(target).startWrite()
        pending.write("{\"version\":".toByteArray())
        pending.fd.sync(); pending.close() // simulate interruption before finishWrite
        val reopened = newJournal(context, device)
        assertEquals("uncertain", reopened.snapshot().decisions.single().result)
        assertNull(reopened.decide(command, expired = false, canStart = true).action)
    }
    fun testWriteFailureLeavesOwnerBrokenAndDoesNotAdmitAnAction() = inFixture { context, files, device ->
        val journal = initialized(context, files, device)
        val dir = File(files, "remote-camera")
        assertTrue(dir.setWritable(false, false))
        try {
            val command = CameraCommand(freshId(), "record_start", freshId(), freshId())
            try { journal.decide(command, expired = false, canStart = true); fail("write unexpectedly succeeded") }
            catch (_: CameraJournalFailureException) { /* no action may be dispatched */ }
            try { journal.snapshot(); fail("broken owner unexpectedly reusable") }
            catch (_: IllegalStateException) { }
        } finally { assertTrue(dir.setWritable(true, true)) }
        val recovered = newJournal(context, device).snapshot()
        assertTrue(recovered.decisions.isEmpty() || recovered.decisions.single().result == "uncertain")
    }
}
