package de.kontrakurs.kontraprompter.cloud

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest

/** Child JVM for an opt-in isolated crash/restart test; never runs in the app. */
object LiveChunkCrashFixture {
    @JvmStatic fun main(args: Array<String>) {
        val mode = args.single()
        val server = requireNotNull(System.getenv("LIVE_FIXTURE_ORIGIN"))
        check(server == "http://127.0.0.1:3391")
        val cookie = requireNotNull(System.getenv("LIVE_FIXTURE_COOKIE"))
        val binding = LiveChunkBinding(requireNotNull(System.getenv("LIVE_FIXTURE_RECORDING")), requireNotNull(System.getenv("LIVE_FIXTURE_PROJECT")), "a".repeat(64))
        val media = File(requireNotNull(System.getenv("LIVE_FIXTURE_MP4")))
        val marker = File(requireNotNull(System.getenv("LIVE_FIXTURE_MARKER")))
        val committed = File(requireNotNull(System.getenv("LIVE_FIXTURE_COMMITTED")))
        fun adapter(address: String) = StudioLiveChunkTransport(cookie, binding.sessionHash, { true }, { path, method ->
            (URI(address + path).toURL().openConnection() as HttpURLConnection).apply { requestMethod = method; connectTimeout = 5_000; readTimeout = 15_000; instanceFollowRedirects = false; useCaches = false }
        }, server)
        fun syncFile(file: File, text: String) { FileOutputStream(file).use { it.write(text.toByteArray()); it.fd.sync() } }
        fun original(): FinalOriginal {
            val bytes = media.readBytes()
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
            return FinalOriginal(bytes.size.toLong(), digest)
        }
        when (mode) {
            "interrupt" -> {
                val proxy = requireNotNull(System.getenv("LIVE_FIXTURE_PROXY"))
                check(runCatching { LiveChunkEngine(adapter(proxy)) { true }.transfer(binding, media) }.isFailure) { "proxy did not interrupt upload" }
                println("network interruption detected; local original retained")
            }
            "commit-and-wait" -> {
                val real = adapter(server); LiveChunkEngine(real) { true }.transfer(binding, media)
                RandomAccessFile(media, "rw").use { it.seek(it.length() - 100); it.writeByte(55) }
                val original = original()
                val blocked = object : LiveChunkTransport by real {
                    override fun commit(binding: LiveChunkBinding, original: FinalOriginal): LiveReceipt {
                        val receipt = real.commit(binding, original)
                        syncFile(committed, "server accepted commit")
                        Thread.sleep(300_000) // parent SIGKILLs before local receipt is processed
                        return receipt
                    }
                }
                LiveChunkEngine(blocked) { true }.transfer(binding, media, original, beforeCommit = {
                    syncFile(marker, "${original.size}\n${original.sha256}\n")
                })
                error("parent did not interrupt child")
            }
            "resume" -> {
                val values = marker.readLines(); check(values.size == 2)
                val expected = FinalOriginal(values[0].toLong(), values[1]); check(expected == original())
                val receipt = LiveChunkEngine(adapter(server)) { true }.transfer(binding, media, expected, commitAttempted = true)
                check(receipt?.id == binding.uploadId && receipt.sha256 == expected.sha256 && receipt.size == expected.size)
                println("read-only receipt matches original after SIGKILL/restart")
            }
            else -> error("invalid fixture mode")
        }
    }
}
