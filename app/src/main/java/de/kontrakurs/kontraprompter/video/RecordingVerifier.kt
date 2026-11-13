package de.kontrakurs.kontraprompter.video

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.os.SystemClock
import java.io.File
import java.security.MessageDigest
import kotlin.math.abs

internal data class VerifiedRecording(val bytes: Long, val durationUs: Long, val sha256: String)

/** Conservative checks: both tracks, chronological samples, decodable media and bounded A/V skew. */
internal object RecordingVerifier {
    private data class Track(val index: Int, val mime: String, val firstUs: Long, val lastUs: Long, val count: Long)
    private const val MAX_SAMPLES = 3_000_000L
    // Catch missing portions at the edges; this does not measure lip sync.
    private const val MAX_SKEW_US = 250_000L
    private const val MAX_PARTIAL_SKEW_US = 8_000_000L
    // The recorder caps capture at 30 minutes; allow time for finalization.
    private const val MAX_TIMESTAMP_US = 32L * 60 * 1_000_000

    fun verify(file: File, partial: Boolean = false): VerifiedRecording {
        val size = file.length()
        check(size in 1..(1024L * 1024 * 1024)) { "Dateigröße der Aufnahme ist ungültig" }
        val extractor = MediaExtractor()
        val video: Track
        val audio: Track
        try {
            extractor.setDataSource(file.absolutePath)
            val tracks = (0 until extractor.trackCount).map { i ->
                i to (extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: "")
            }
            val v = tracks.firstOrNull { it.second == "video/avc" } ?: error("H.264-Videospur fehlt")
            val a = tracks.firstOrNull { it.second == "audio/mp4a-latm" } ?: error("AAC-Tonspur fehlt")
            video = inspect(extractor, v.first, v.second)
            audio = inspect(extractor, a.first, a.second)
        } finally { extractor.release() }
        check(video.count >= 2 && audio.count >= 2 && video.lastUs - video.firstUs >= 500_000 && audio.lastUs - audio.firstUs >= 500_000) { "Aufnahme ist zu kurz oder unvollständig" }
        val allowedSkew = if (partial) MAX_PARTIAL_SKEW_US else MAX_SKEW_US
        check(abs(video.firstUs - audio.firstUs) <= allowedSkew && abs(video.lastUs - audio.lastUs) <= allowedSkew) { "Video- und Tonspur beginnen oder enden zu weit auseinander" }
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            val image = retriever.getFrameAtTime(video.firstUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: error("Videobild konnte nicht dekodiert werden")
            image.recycle()
        } finally { retriever.release() }
        check(decodeAudio(file, audio.index)) { "Tonspur konnte nicht dekodiert werden" }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(256 * 1024).use { stream ->
            val block = ByteArray(256 * 1024)
            while (true) { val count = stream.read(block); if (count < 0) break; digest.update(block, 0, count) }
        }
        val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return VerifiedRecording(size, maxOf(video.lastUs, audio.lastUs), hash)
    }

    private fun inspect(extractor: MediaExtractor, index: Int, mime: String): Track {
        extractor.selectTrack(index)
        try {
            extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            var first = -1L; var last = -1L; var count = 0L
            while (extractor.sampleTrackIndex >= 0 && extractor.sampleTime >= 0) {
                val timestamp = extractor.sampleTime
                check(timestamp in 0..MAX_TIMESTAMP_US && (last < 0 || timestamp > last)) { "Ungeordnete oder ungültige Medienzeitstempel" }
                if (first < 0) first = timestamp
                last = timestamp; count++
                check(count <= MAX_SAMPLES) { "Aufnahme enthält zu viele Samples" }
                if (!extractor.advance()) break
            }
            return Track(index, mime, first, last, count)
        } finally { extractor.unselectTrack(index) }
    }

    private fun decodeAudio(file: File, index: Int): Boolean {
        val extractor = MediaExtractor(); var decoder: MediaCodec? = null
        try {
            extractor.setDataSource(file.absolutePath); extractor.selectTrack(index)
            val format = extractor.getTrackFormat(index)
            decoder = MediaCodec.createDecoderByType("audio/mp4a-latm")
            decoder.configure(format, null, null, 0); decoder.start()
            val info = MediaCodec.BufferInfo(); var inputDone = false
            val until = SystemClock.elapsedRealtime() + 5_000
            while (SystemClock.elapsedRealtime() < until) {
                if (!inputDone) {
                    val inputIndex = decoder.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val buffer = decoder.getInputBuffer(inputIndex) ?: return false
                        buffer.clear(); val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) { decoder.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                        else { decoder.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0); extractor.advance() }
                    }
                }
                val outputIndex = decoder.dequeueOutputBuffer(info, 10_000)
                if (outputIndex >= 0) {
                    val decoded = info.size > 0
                    val end = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    decoder.releaseOutputBuffer(outputIndex, false)
                    if (decoded) return true
                    if (end) return false
                }
            }
            return false
        } finally {
            runCatching { decoder?.stop() }; runCatching { decoder?.release() }; extractor.release()
        }
    }
}
