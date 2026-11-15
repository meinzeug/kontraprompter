package de.kontrakurs.kontraprompter.cloud

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Explicitly opt-in: runs only against an isolated server and a synthetic MP4. */
class LiveChunkHttpTest {
    @Test fun `real HTTP original chunks recover a lost commit response by read-only receipt`() {
        val base = System.getenv("LIVE_FIXTURE_ORIGIN")
        val cookie = System.getenv("LIVE_FIXTURE_COOKIE")
        val project = System.getenv("LIVE_FIXTURE_PROJECT")
        val input = System.getenv("LIVE_FIXTURE_MP4")
        val recording = System.getenv("LIVE_FIXTURE_RECORDING")
        assumeTrue(base != null && cookie != null && project != null && input != null && recording != null)
        check(base == "http://127.0.0.1:3391") { "Only the isolated local test server is allowed" }
        val file = File.createTempFile("live-http-fixture-", ".mp4")
        try {
            File(checkNotNull(input)).copyTo(file, overwrite = true)
            check(file.length() > LiveChunkEngine.CHUNK)
            val binding = LiveChunkBinding(checkNotNull(recording), checkNotNull(project), "a".repeat(64))
            val adapter = StudioLiveChunkTransport(checkNotNull(cookie), binding.sessionHash, { true }, { path, method ->
                (URL(base + path).openConnection() as HttpURLConnection).apply {
                    requestMethod = method; connectTimeout = 5_000; readTimeout = 30_000; instanceFollowRedirects = false; useCaches = false
                }
            }, checkNotNull(base))
            val engine = LiveChunkEngine(adapter) { true }
            assertNull(engine.transfer(binding, file))
            // A valid MP4 free atom is included by the fixture; rewriting a byte of
            // its padding models MediaMuxer's ability to rewrite earlier offsets.
            RandomAccessFile(file, "rw").use { media -> media.seek(media.length() - 100); media.writeByte(37) }
            val bytes = file.readBytes()
            val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
            val original = FinalOriginal(bytes.size.toLong(), sha)
            var marked = false
            val loseResponse = object : LiveChunkTransport by adapter {
                override fun commit(binding: LiveChunkBinding, original: FinalOriginal): LiveReceipt {
                    adapter.commit(binding, original)
                    throw IOException("Simulated lost commit response")
                }
            }
            assertTrue(runCatching { LiveChunkEngine(loseResponse) { true }.transfer(binding, file, original, beforeCommit = { marked = true }) }.isFailure)
            assertTrue(marked)
            val receipt = LiveChunkEngine(adapter) { true }.transfer(binding, file, original, commitAttempted = true)
            assertEquals(binding.uploadId, receipt?.id); assertEquals(original.sha256, receipt?.sha256); assertEquals(original.size, receipt?.size)
            assertEquals(sha, MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) })
        } finally { file.delete() }
    }
}
