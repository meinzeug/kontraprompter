package de.kontrakurs.kontraprompter.cloud

/** Process-only session owner; values and attempts are compared by identity, not bearer text. */
internal class SessionCookie {
    internal class Snapshot internal constructor(internal val value: String)
    internal class Attempt internal constructor()
    private var current: Snapshot? = null
    private var pending: Attempt? = null
    private var tombstone = false

    @Synchronized fun get(): Snapshot? = current
    @Synchronized fun beginLogin(): Attempt {
        current = null
        return Attempt().also { pending = it }
    }
    @Synchronized fun complete(attempt: Attempt, value: String): Boolean {
        if (pending !== attempt || value.isBlank()) return false
        pending = null
        current = Snapshot(value)
        tombstone = false
        return true
    }
    @Synchronized fun abandon(attempt: Attempt) { if (pending === attempt) pending = null }
    @Synchronized fun logoutLocal() { pending = null; current = null; tombstone = true }
    @Synchronized fun invalidate(snapshot: Snapshot) { if (current === snapshot) current = null }
    @Synchronized fun isCurrent(snapshot: Snapshot): Boolean = current === snapshot
    @Synchronized fun isTombstoned(): Boolean = tombstone
}

internal enum class SessionOwner { STUDIO_UPLOAD, CAMERA_REMOTE }

/** Explicit owner partitions; not connected by default to production clients. */
internal class SessionRegistry {
    private val studio = SessionCookie()
    private val camera = SessionCookie()
    fun slot(owner: SessionOwner): SessionCookie = when (owner) {
        SessionOwner.STUDIO_UPLOAD -> studio
        SessionOwner.CAMERA_REMOTE -> camera
    }
}

/** Disabled pending end-to-end, logout and upload-journal fencing. */
internal object ExperimentalStudioSessions {
    private const val ENABLED = false
    private val registry = SessionRegistry()
    fun client(owner: SessionOwner): StudioClient =
        if (ENABLED) StudioClient(registry, owner) else StudioClient()
}
