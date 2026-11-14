package de.kontrakurs.kontraprompter.camera

/** Count from request start: response latency may never extend the server's lease. */
internal fun previewLeaseDeadline(started: Long, now: Long, remaining: Long): Long? {
    if (started < 0 || now < started || remaining !in 1..12_000 || started > Long.MAX_VALUE - 9_000) return null
    val deadline = started + minOf(remaining, 9_000L)
    return deadline.takeIf { it > now }
}

/** A delayed watchdog observation must not revoke a replacement or renewed lease. */
internal fun previewLeaseMayExpire(observedId: String, observedDeadline: Long, currentId: String?, currentDeadline: Long, now: Long): Boolean =
    observedId == currentId && observedDeadline == currentDeadline && now >= currentDeadline
