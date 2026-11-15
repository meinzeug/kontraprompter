package de.kontrakurs.kontraprompter.cloud

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Experimental server adapter; not wired to the app and never enabled in production. */
internal class StudioLiveChunkTransport(
    private val cookie: String,
    private val sessionBinding: String,
    private val sessionMatches: () -> Boolean,
    private val open: (String, String) -> HttpURLConnection,
    private val origin: String = "https://videomaker.dorfspy.de",
) : LiveChunkTransport {
    private fun request(binding: LiveChunkBinding, method: String, query: String = "", headers: Map<String, String> = emptyMap(), body: ByteArray? = null): JSONObject {
        check(binding.sessionHash == sessionBinding && sessionMatches()) { "Studio session changed" }
        val connection = open("/api/projects/${binding.projectId}/live-recordings/${binding.uploadId}$query", method)
        val timedOut = AtomicBoolean(false)
        val watchdog = Executors.newSingleThreadScheduledExecutor { runnable -> Thread(runnable, "KontraLiveUploadWatchdog").apply { isDaemon = true } }
        fun checkSession() { check(!timedOut.get() && sessionMatches()) { "Studio session changed or live upload timed out" } }
        try {
            watchdog.schedule({
                timedOut.set(true)
                Thread({ runCatching { connection.disconnect() } }, "KontraLiveUploadCancel").apply { isDaemon = true }.start()
            }, 60, TimeUnit.SECONDS)
            connection.setRequestProperty("Cookie", cookie)
            connection.setRequestProperty("Origin", origin)
            connection.setRequestProperty("X-Recording-Id", binding.recordingId)
            connection.setRequestProperty("X-Live-Origin", "lan")
            headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            if (body != null) {
                check(body.size in 1..LiveChunkEngine.CHUNK)
                connection.setRequestProperty("Content-Type", "application/octet-stream")
                connection.doOutput = true; connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { output ->
                    var offset = 0
                    while (offset < body.size) {
                        checkSession()
                        val length = minOf(64 * 1024, body.size - offset)
                        output.write(body, offset, length); offset += length
                        checkSession()
                    }
                }
            }
            val code = connection.responseCode
            checkSession()
            if (code !in 200..299) throw IOException("Live upload response HTTP $code; original remains local")
            val text = connection.inputStream.bufferedReader(Charsets.UTF_8).use { input ->
                val output = StringBuilder(); val chars = CharArray(1024)
                while (true) { val n = input.read(chars); if (n < 0) break; check(output.length + n <= 16_384); output.append(chars, 0, n) }
                output.toString()
            }
            checkSession()
            return JSONObject(text)
        } finally { watchdog.shutdownNow(); connection.disconnect() }
    }
    override fun begin(binding: LiveChunkBinding): Int = request(binding, "POST", headers = mapOf("X-Live-Operation" to "begin")).getInt("chunkBytes")
    override fun status(binding: LiveChunkBinding, after: Int?): LivePage {
        val query = if (after == null) "?limit=16" else "?after=$after&limit=16"
        val response = request(binding, "GET", query)
        val chunks = response.getJSONArray("chunks")
        check(chunks.length() <= 16)
        val values = (0 until chunks.length()).map { i -> chunks.getJSONObject(i).let { part ->
            LivePart(part.getInt("part"), part.getLong("revision"), part.getLong("expected_revision"), part.getInt("size"), part.getString("sha256"), part.getString("state"))
        } }
        return LivePage(response.getString("state"), if (response.isNull("finalSize")) null else response.getLong("finalSize"),
            if (response.isNull("finalSha256")) null else response.getString("finalSha256"), values, response.getBoolean("hasMore"))
    }
    override fun put(binding: LiveChunkBinding, index: Int, expectedRevision: Long, bytes: ByteArray, sha256: String): LivePartAck {
        val response = request(binding, "PUT", headers = mapOf("X-Live-Part" to index.toString(), "X-Live-Revision" to expectedRevision.toString(), "X-Live-Sha256" to sha256), body = bytes)
        return LivePartAck(response.getLong("revision"), response.getString("sha256"))
    }
    private fun finalHeaders(operation: String, original: FinalOriginal) = mapOf("X-Live-Operation" to operation, "X-Live-Finalized" to "1", "X-Live-Final-Size" to original.size.toString(), "X-Live-Final-Sha256" to original.sha256)
    override fun seal(binding: LiveChunkBinding, original: FinalOriginal): LiveSeal {
        val response = request(binding, "POST", headers = finalHeaders("seal", original))
        return LiveSeal(response.getString("state"), response.getBoolean("published"), response.getLong("sizeBytes"), response.getString("sha256"))
    }
    override fun receipt(binding: LiveChunkBinding): LiveReceipt {
        val response = request(binding, "GET", "?receipt=1")
        check(response.getString("recordingId") == binding.recordingId)
        return LiveReceipt(response.getString("id"), response.getString("projectId"), response.getLong("sizeBytes"), response.getString("sha256"), response.getBoolean("published"))
    }
    override fun commit(binding: LiveChunkBinding, original: FinalOriginal): LiveReceipt {
        val response = request(binding, "POST", headers = finalHeaders("commit", original))
        return LiveReceipt(response.getString("id"), response.getString("projectId"), response.getLong("sizeBytes"), response.getString("sha256"), response.getBoolean("published"))
    }
}
