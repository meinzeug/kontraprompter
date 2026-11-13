package de.kontrakurs.kontraprompter.video

/** Shared monotonic time base for camera frames and microphone samples. */
internal class RecordingTimeline(private val originNs: Long) {
    init { require(originNs > 0) }

    fun videoTimestampNs(captureNs: Long): Long? = relativeNs(captureNs)

    /** AudioTimestamp.nanoTime refers to framePosition, not the most recent read. */
    fun audioTimestampUs(timestampNs: Long, framePosition: Long, firstReadFrame: Long, sampleRate: Int): Long? {
        require(timestampNs > 0 && framePosition >= 0 && firstReadFrame >= 0 && sampleRate in 8_000..192_000)
        val frameDelta = Math.subtractExact(firstReadFrame, framePosition)
        val seconds = frameDelta / sampleRate
        val remainder = frameDelta % sampleRate
        val offset = Math.addExact(Math.multiplyExact(seconds, 1_000_000_000L), Math.multiplyExact(remainder, 1_000_000_000L) / sampleRate)
        return relativeNs(Math.addExact(timestampNs, offset))?.div(1_000L)
    }

    private fun relativeNs(absoluteNs: Long): Long? {
        require(absoluteNs > 0)
        return if (absoluteNs < originNs) null else absoluteNs - originNs
    }
}

/** Each encoded track must be chronological; losing timestamp order is an error. */
internal class RecordingSampleLedger(private val maxBytes: Long) {
    init { require(maxBytes > 0) }
    private val lastTimestampUs = longArrayOf(-1, -1)
    private val firstTimestampUs = longArrayOf(-1, -1)
    private val samples = longArrayOf(0, 0)
    var bytes: Long = 0
        private set

    @Synchronized fun add(track: Int, timestampUs: Long, size: Int) {
        require(track in 0..1 && timestampUs >= 0 && size > 0)
        check(timestampUs > lastTimestampUs[track]) { "Ungeordnetes Zeitstempel-Sample" }
        check(size.toLong() <= maxBytes - bytes) { "Aufnahmegrößenlimit erreicht" }
        bytes += size
        if (samples[track] == 0L) firstTimestampUs[track] = timestampUs
        lastTimestampUs[track] = timestampUs
        samples[track]++
    }

    @Synchronized fun snapshot(): TrackSummary = TrackSummary(
        samples[0], samples[1], firstTimestampUs[0], firstTimestampUs[1], lastTimestampUs[0], lastTimestampUs[1], bytes
    )
}

internal data class TrackSummary(
    val videoSamples: Long, val audioSamples: Long,
    val firstVideoUs: Long, val firstAudioUs: Long,
    val lastVideoUs: Long, val lastAudioUs: Long,
    val bytes: Long,
)
