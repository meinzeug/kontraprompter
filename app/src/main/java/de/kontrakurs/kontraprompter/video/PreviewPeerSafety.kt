package de.kontrakurs.kontraprompter.video

/** Unknown native cleanup remains blocked across camera pipelines in this app process. */
internal class PreviewPeerSafety {
    @Volatile var cleanupUnknown = false
        private set

    fun markCleanupUnknown() { cleanupUnknown = true }
    fun mayStart(): Boolean = !cleanupUnknown
    fun mayConfirmNoPeer(): Boolean = !cleanupUnknown
}
