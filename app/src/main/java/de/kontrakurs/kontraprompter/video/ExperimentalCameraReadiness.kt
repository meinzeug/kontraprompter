package de.kontrakurs.kontraprompter.video

/** Debug-only camera-switch readiness. Mutations from camera/owner threads are serialized. */
internal class ExperimentalCameraReadiness {
    private var generation = 0L
    private var switching = false
    private var ready = false
    private var acceptingFrames = true
    @Synchronized fun beginSwitch(): Long? {
        if (switching || generation == Long.MAX_VALUE) return null
        generation++
        switching = true
        ready = false
        return generation
    }
    @Synchronized fun finishSwitch(token: Long): Boolean {
        if (!switching || generation != token) return false
        switching = false
        ready = false // Only a later camera frame can restore preview readiness.
        return true
    }
    @Synchronized fun frame() { if (!switching && acceptingFrames) ready = true }
    @Synchronized fun opening() { ready = false; acceptingFrames = true }
    @Synchronized fun invalidate() { ready = false; acceptingFrames = false }
    @Synchronized fun isReady(): Boolean = ready && !switching
}
