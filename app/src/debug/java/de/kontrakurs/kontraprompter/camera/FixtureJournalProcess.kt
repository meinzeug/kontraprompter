package de.kontrakurs.kontraprompter.camera

import android.app.Service
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import java.io.File
import java.util.UUID

/** Exists only in the isolated fixture debug APK, never in the release application. */
class FixtureJournalProcess : Service() {
    override fun onBind(intent: Intent): IBinder {
        check(packageName.endsWith(".journalfixture"))
        val name = checkNotNull(intent.getStringExtra("folder"))
        check(name.matches(Regex("journal-fixture-[0-9a-f-]{36}")))
        val files = File(cacheDir, name).canonicalFile
        check(files.parentFile == cacheDir.canonicalFile && files.isDirectory)
        val device = checkNotNull(intent.getStringExtra("device"))
        val command = CameraCommand(checkNotNull(intent.getStringExtra("command")), "record_start",
            checkNotNull(intent.getStringExtra("recording")), checkNotNull(intent.getStringExtra("project")))
        listOf(device, command.id, command.recordingId, command.projectId!!).forEach { check(UUID.fromString(it).toString() == it) }
        val isolated = object : ContextWrapper(this) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = files
        }
        val journal = RemoteCameraJournal.open(isolated, device)
        check(journal.decide(command, expired = false, canStart = true).result == "admitting")
        File(files, "fixture-pid").outputStream().use { out ->
            out.write(android.os.Process.myPid().toString().toByteArray()); out.flush(); out.fd.sync()
        }
        return Binder()
    }
}
