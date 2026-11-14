package de.kontrakurs.kontraprompter.cloud

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class LiveChunkEngineTest {
    private val binding = LiveChunkBinding("8f991102-1260-4349-a59e-d65e80a80db0", "835c2eca-7783-4b90-9a23-ff20f43a53b8", "a".repeat(64))
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private class Fake : LiveChunkTransport {
        val data = sortedMapOf<Int, ByteArray>(); val revisions = sortedMapOf<Int, Long>()
        var state = "open"; var final: FinalOriginal? = null; var puts = 0; var commits = 0
        var loseAck = false; var wrongReceipt = false; var onPut: (() -> Unit)? = null
        override fun begin(binding: LiveChunkBinding) = LiveChunkEngine.CHUNK
        override fun status(binding: LiveChunkBinding, after: Int?): LivePage {
            val selected = data.keys.filter { it > (after ?: -1) }.take(16)
            val parts = selected.map { index -> val bytes = data.getValue(index); LivePart(index, revisions.getValue(index), revisions.getValue(index) - 1, bytes.size, hash(bytes), "ready") }
            return LivePage(state, final?.size, final?.sha256, parts, data.keys.any { it > (selected.lastOrNull() ?: (after ?: -1)) })
        }
        override fun put(binding: LiveChunkBinding, index: Int, expectedRevision: Long, bytes: ByteArray, sha256: String): LivePartAck {
            check((revisions[index] ?: 0) == expectedRevision && hash(bytes) == sha256)
            data[index] = bytes.copyOf(); revisions[index] = expectedRevision + 1; puts++; onPut?.invoke()
            if (loseAck) { loseAck = false; error("lost ACK") }
            return LivePartAck(expectedRevision + 1, sha256)
        }
        override fun seal(binding: LiveChunkBinding, original: FinalOriginal): LiveSeal {
            val all = data.values.fold(ByteArray(0)) { a, b -> a + b }
            check(all.size.toLong() == original.size && hash(all) == original.sha256)
            final = original; state = "sealed"; return LiveSeal(state, false, original.size, original.sha256)
        }
        override fun commit(binding: LiveChunkBinding, original: FinalOriginal): LiveReceipt {
            commits++; state = "published"
            return LiveReceipt(if (wrongReceipt) binding.recordingId else binding.uploadId, binding.projectId, original.size, original.sha256, true)
        }
        private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    private inline fun withFile(block: (File) -> Unit) { val f = File.createTempFile("live-chunk-", ".mp4"); try { block(f) } finally { f.delete() } }
    @Test fun `live uploads full chunks and rewrites changed MP4 header after stop`() = withFile { file ->
        val bytes = ByteArray(LiveChunkEngine.CHUNK + 123) { (it % 251).toByte() }; file.writeBytes(bytes)
        val fake = Fake(); val engine = LiveChunkEngine(fake) { true }
        assertNull(engine.transfer(binding, file)); assertEquals(1, fake.puts)
        bytes[0] = 77; file.writeBytes(bytes)
        val receipt = engine.transfer(binding, file, FinalOriginal(bytes.size.toLong(), sha(bytes)))
        assertEquals(3, fake.puts); assertEquals(2L, fake.revisions[0]); assertEquals(binding.uploadId, receipt?.id)
        assertArrayEquals(bytes, fake.data.values.fold(ByteArray(0)) { a, b -> a + b })
    }
    @Test fun `lost acknowledgement resumes from server status without repeating chunk`() = withFile { file ->
        val bytes = ByteArray(LiveChunkEngine.CHUNK) { 8 }; file.writeBytes(bytes)
        val fake = Fake().apply { loseAck = true }; val engine = LiveChunkEngine(fake) { true }
        assertTrue(runCatching { engine.transfer(binding, file) }.isFailure)
        engine.transfer(binding, file); assertEquals(1, fake.puts)
    }
    @Test fun `session change and wrong receipt cannot be confirmed`() = withFile { file ->
        val bytes = ByteArray(100) { 9 }; file.writeBytes(bytes); val final = FinalOriginal(bytes.size.toLong(), sha(bytes))
        val fake = Fake(); var allowed = true; fake.onPut = { allowed = false }
        assertTrue(runCatching { LiveChunkEngine(fake) { allowed }.transfer(binding, file, final) }.isFailure)
        assertEquals(0, fake.commits); allowed = true; fake.onPut = null; fake.wrongReceipt = true
        assertTrue(runCatching { LiveChunkEngine(fake) { allowed }.transfer(binding, file, final) }.isFailure)
    }
    @Test fun `changed final file is never sealed`() = withFile { file ->
        val bytes = ByteArray(100) { 1 }; file.writeBytes(bytes); val fake = Fake()
        val original = FinalOriginal(bytes.size.toLong(), sha(bytes)); bytes[0] = 2; file.writeBytes(bytes)
        assertTrue(runCatching { LiveChunkEngine(fake) { true }.transfer(binding, file, original) }.isFailure)
        assertEquals(0, fake.puts); assertEquals("open", fake.state)
    }
    @Test fun `published server state does not resurrect a deleted upload`() = withFile { file ->
        val bytes = ByteArray(100) { 2 }; file.writeBytes(bytes); val fake = Fake().apply { state = "published" }
        assertNull(LiveChunkEngine(fake) { true }.transfer(binding, file, FinalOriginal(bytes.size.toLong(), sha(bytes))))
        assertEquals(0, fake.commits)
    }
}
