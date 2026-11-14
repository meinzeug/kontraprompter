package de.kontrakurs.kontraprompter.video

/**
 * Projects the shared monotonic camera/microphone clock onto the recorded timeline.
 * Samples touching a paused interval are discarded rather than timestamped across it.
 * A completed interval is retained so late camera frames cannot bypass a pause.
 */
internal class RecordingPauseTimeline(private val originNs: Long) {
    private data class Interval(val start: Long, val end: Long, val removedThroughEnd: Long)
    private val intervals = ArrayList<Interval>()
    private var open: Long? = null
    init { require(originNs > 0) }

    @Synchronized fun pause(atNs: Long): Boolean {
        require(atNs >= originNs)
        if (open != null || intervals.lastOrNull()?.end?.let { atNs < it } == true) return false
        open = atNs
        return true
    }

    @Synchronized fun resume(atNs: Long): Boolean {
        val start = open ?: return false
        if (atNs <= start) return false
        val removed = Math.addExact(intervals.lastOrNull()?.removedThroughEnd ?: 0L, Math.subtractExact(atNs, start))
        intervals.add(Interval(start, atNs, removed))
        open = null
        return true
    }

    @Synchronized fun isPaused(): Boolean = open != null

    /** End is exclusive. A zero-span camera frame is classified by its capture time. */
    @Synchronized fun project(captureNs: Long, spanNs: Long = 0L): Long? {
        require(captureNs > 0 && spanNs >= 0)
        if (captureNs < originNs) return null
        val end = Math.addExact(captureNs, spanNs)
        open?.let { if (captureNs >= it || end > it) return null }
        var lo = 0
        var hi = intervals.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (intervals[mid].end <= captureNs) lo = mid + 1 else hi = mid
        }
        if (lo < intervals.size) {
            val next = intervals[lo]
            if (captureNs >= next.start || end > next.start) return null
        }
        val removed = if (lo == 0) 0L else intervals[lo - 1].removedThroughEnd
        return Math.subtractExact(Math.subtractExact(captureNs, originNs), removed)
    }
}
