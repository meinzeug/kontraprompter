package de.kontrakurs.kontraprompter.cloud

import android.content.Context
import android.system.Os
import android.system.OsConstants
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.util.UUID

internal data class LiveBindingEntry(val binding: LiveChunkBinding, val confirmed: Boolean, val size: Long? = null, val sha256: String? = null)

/** Durable upload-path fence. Never stores cookies, media or scripts. */
internal class LiveChunkBindingStore(context: Context) {
    companion object { private val lock = Any() }
    private val root = File(context.applicationContext.filesDir, "live-upload-bindings")
    private fun target(recording: String): File {
        require(UUID.fromString(recording).toString() == recording)
        check((root.exists() || root.mkdirs()) && root.isDirectory)
        return File(root, "$recording.json")
    }
    fun get(recording: String): LiveBindingEntry? = synchronized(lock) {
        val target = target(recording)
        if (!target.exists() && !File(target.path + ".bak").exists()) return@synchronized null
        val text = AtomicFile(target).openRead().bufferedReader(Charsets.UTF_8).use { reader ->
            val output = StringBuilder(); val block = CharArray(1024)
            while (true) { val n = reader.read(block); if (n < 0) break; check(output.length + n <= 8192); output.append(block, 0, n) }; output.toString()
        }
        val json = JSONObject(text); check(json.getInt("version") == 1)
        val binding = LiveChunkBinding(json.getString("recordingId"), json.getString("projectId"), json.getString("sessionHash"))
        check(binding.recordingId == recording)
        val confirmed = json.getBoolean("confirmed")
        val size = if (json.isNull("sizeBytes")) null else json.getLong("sizeBytes")
        val sha = if (json.isNull("sha256")) null else json.getString("sha256")
        if (confirmed) check(size != null && size in 1..LiveChunkEngine.MAX_SIZE && sha != null && sha.matches(Regex("[0-9a-f]{64}")))
        else check(size == null && sha == null)
        LiveBindingEntry(binding, confirmed, size, sha)
    }
    /** The live path is selected before begin, and cannot silently change its destination or owner. */
    fun bind(binding: LiveChunkBinding): LiveBindingEntry = synchronized(lock) {
        val current = get(binding.recordingId)
        if (current != null) { check(current.binding == binding); return@synchronized current }
        LiveBindingEntry(binding, false).also { write(it) }
    }
    fun confirm(binding: LiveChunkBinding, original: FinalOriginal, receipt: LiveReceipt): LiveBindingEntry = synchronized(lock) {
        val current = checkNotNull(get(binding.recordingId)); check(current.binding == binding)
        check(receipt.published && receipt.id == binding.uploadId && receipt.projectId == binding.projectId && receipt.size == original.size && receipt.sha256 == original.sha256)
        val entry = LiveBindingEntry(binding, true, original.size, original.sha256)
        if (current.confirmed) { check(current == entry); return@synchronized current }
        entry.also { write(it) }
    }
    private fun write(entry: LiveBindingEntry) {
        val target = target(entry.binding.recordingId); val atomic = AtomicFile(target)
        val json = JSONObject().put("version", 1).put("recordingId", entry.binding.recordingId).put("projectId", entry.binding.projectId)
            .put("sessionHash", entry.binding.sessionHash).put("confirmed", entry.confirmed)
            .put("sizeBytes", entry.size ?: JSONObject.NULL).put("sha256", entry.sha256 ?: JSONObject.NULL)
        val stream = atomic.startWrite()
        try { stream.write(json.toString().toByteArray(Charsets.UTF_8)); stream.fd.sync(); atomic.finishWrite(stream) }
        catch (error: Exception) { atomic.failWrite(stream); throw error }
        for (dir in listOf(root, root.parentFile!!)) {
            val descriptor = Os.open(dir.absolutePath, OsConstants.O_RDONLY, 0)
            try { check(OsConstants.S_ISDIR(Os.fstat(descriptor).st_mode)); Os.fsync(descriptor) } finally { Os.close(descriptor) }
        }
    }
}
