package de.kontrakurs.kontraprompter

/** Only authoritative camera states control playback; uncertain transitions leave it alone. */
internal fun recordingScrollPlayback(phase: String): Boolean? = when (phase) {
    "recording" -> true
    "paused", "finalizing", "ready", "partial", "failed" -> false
    else -> null
}
