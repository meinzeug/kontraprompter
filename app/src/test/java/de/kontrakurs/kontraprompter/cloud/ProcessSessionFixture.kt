package de.kontrakurs.kontraprompter.cloud

/** Test-only process coordinator: no Android storage, network, or actual client integration. */
internal class ProcessSessionFixture {
    internal class Attempt internal constructor()
    internal class Snapshot internal constructor(internal val cookie: String)
    private var pending: Attempt? = null
    private var current: Snapshot? = null
    private var logoutTombstone = false
    private var restoreBlocked = false
    private var inLocalCommit = false

    /** Explicit login may follow logout; automatic restore cannot bypass its tombstone. */
    @Synchronized fun beginLogin(): Attempt {
        check(!inLocalCommit)
        current = null
        return Attempt().also { pending = it }
    }

    @Synchronized fun beginRestore(): Attempt? {
        check(!inLocalCommit)
        if (restoreBlocked || pending != null || current != null) return null
        return Attempt().also { pending = it }
    }

    /** A caller must have verified a restored session with the real server first. */
    @Synchronized fun complete(attempt: Attempt, cookie: String, serverVerified: Boolean): Snapshot? {
        check(!inLocalCommit)
        if (pending !== attempt) return null
        pending = null
        if (!serverVerified || cookie.isBlank()) return null
        return Snapshot(cookie).also { current = it; logoutTombstone = false; restoreBlocked = false }
    }

    @Synchronized fun logout() {
        check(!inLocalCommit)
        pending = null
        current = null
        logoutTombstone = true
        restoreBlocked = true
    }

    @Synchronized fun snapshot(): Snapshot? = current
    @Synchronized fun loggedOut(): Boolean = logoutTombstone

    /** An old 401 cannot clear a newer session; a current one clears only itself. */
    @Synchronized fun unauthorized(snapshot: Snapshot): Boolean {
        check(!inLocalCommit)
        if (current !== snapshot) return false
        current = null
        restoreBlocked = true
        return true
    }

    /** Only a short, in-memory local commit may run here; never network, disk, or UI callbacks. */
    @Synchronized fun applyIfCurrent(snapshot: Snapshot, localCommit: () -> Unit): Boolean {
        check(!inLocalCommit)
        if (current !== snapshot) return false
        inLocalCommit = true
        try { localCommit() } finally { inLocalCommit = false }
        return true
    }
}
