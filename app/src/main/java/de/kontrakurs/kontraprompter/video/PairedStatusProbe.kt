package de.kontrakurs.kontraprompter.video

/** A point-in-time observation, never an authorization to operate a camera. */
internal data class PairedStatusObservation(
    val revision: Long,
    val phase: String,
    val recordingId: String?,
    val projectKnown: Boolean,
    val projectId: String?,
    val cameraFront: Boolean?,
)

/** Caller serializes channel admission with connection replacement; this class protects request state. */
internal class PairedStatusProbe(private val ttlMs: Long = 5_000) {
    private data class Pending(val marker: Any, val request: String, val started: Long)
    private var pending: Pending? = null
    private data class Result(val marker: Any, val started: Long, val value: PairedStatusObservation)
    private var result: Result? = null
    private var lastMarker: Any? = null
    private var lastRevision = -1L

    @Synchronized fun begin(marker: Any, request: String, now: Long): Boolean {
        pending?.let { if (now >= it.started && now - it.started < ttlMs) return false }
        pending = null
        if (lastMarker !== marker) { lastMarker = marker; lastRevision = -1 }
        result = null
        pending = Pending(marker, request, now)
        return true
    }

    @Synchronized fun receive(marker: Any, request: String, now: Long, value: PairedStatusObservation): Boolean {
        val p = pending ?: return false
        if (p.marker !== marker || p.request != request) return false
        // Do not allow elapsedRealtime rollback or overflow to revive an expired request.
        if (now < p.started || now - p.started >= ttlMs) { pending = null; return false }
        if (value.revision <= lastRevision) return false
        pending = null
        lastRevision = value.revision
        result = Result(marker, p.started, value)
        return true
    }

    @Synchronized fun result(marker: Any, now: Long): PairedStatusObservation? = result?.takeIf {
        it.marker === marker && now >= it.started && now - it.started < ttlMs
    }?.value
    @Synchronized fun cancel(marker: Any, request: String) {
        if (pending?.let { it.marker === marker && it.request == request } == true) pending = null
    }
    @Synchronized fun reset() { pending = null; result = null; lastMarker = null; lastRevision = -1 }
}
