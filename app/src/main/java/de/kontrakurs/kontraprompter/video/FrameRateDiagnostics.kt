package de.kontrakurs.kontraprompter.video

/** Bounded recording-local aggregates; no frames or per-frame timestamp history. */
internal class FrameRateDiagnostics {
    data class Summary(
        val frames: Long, val invalidTimestamps: Long, val elapsedNs: Long,
        val medianIntervalMs: Int?, val p95IntervalMs: Int?, val maxIntervalMs: Long?,
    ) {
        val fps: Double? get() = if (frames > 1 && elapsedNs > 0) (frames - 1) * 1_000_000_000.0 / elapsedNs else null
    }

    // Bucket 1001 collects all gaps of 1001 ms or more; max keeps the true maximum.
    private val buckets = LongArray(1002)
    private var frames = 0L
    private var invalid = 0L
    private var first = -1L
    private var last = -1L
    private var maxGapNs = 0L

    @Synchronized fun add(timestampNs: Long) {
        if (timestampNs < 0 || (last >= 0 && timestampNs <= last)) { invalid++; return }
        if (first < 0) first = timestampNs
        if (last >= 0) {
            val gap = timestampNs - last
            maxGapNs = maxOf(maxGapNs, gap)
            buckets[(gap / 1_000_000L).coerceAtMost(1001).toInt()]++
        }
        last = timestampNs
        frames++
    }

    @Synchronized fun summary(): Summary {
        fun quantile(numerator: Int): Int? {
            val gaps = frames - 1
            if (gaps <= 0) return null
            val target = (gaps * numerator + 99) / 100
            var total = 0L
            for (index in buckets.indices) { total += buckets[index]; if (total >= target) return index }
            return null
        }
        return Summary(frames, invalid, if (frames > 1) last - first else 0, quantile(50), quantile(95), if (frames > 1) maxGapNs / 1_000_000L else null)
    }
}

/** Exactly one rejection reason is recorded for each classified recorder input. */
internal class CaptureFrameDiagnostics {
    enum class Rejection { PREPARING, INVALID_TIME, ROTATION, NO_OFFSET, DUPLICATE, PROJECTED_OUT, NO_RENDERER }
    private val arrival = FrameRateDiagnostics()
    private val accepted = FrameRateDiagnostics()
    private val rejected = LongArray(Rejection.entries.size)
    private var observedPaused = 0L

    @Synchronized fun arriving(nowNs: Long, paused: Boolean) {
        arrival.add(nowNs)
        if (paused) observedPaused++
    }
    fun accepted(timestampNs: Long) = accepted.add(timestampNs)
    @Synchronized fun rejected(reason: Rejection) { rejected[reason.ordinal]++ }
    @Synchronized fun rejections(): List<Long> = rejected.toList()
    @Synchronized fun pausedFrames(): Long = observedPaused
    fun arrivals(): FrameRateDiagnostics.Summary = arrival.summary()
    fun acceptedFrames(): FrameRateDiagnostics.Summary = accepted.summary()
}
