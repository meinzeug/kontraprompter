package de.kontrakurs.kontraprompter.video

/** Debug-only camera-switch readiness. Mutations from camera/owner threads are serialized. */
internal class ExperimentalCameraReadiness {
    private var generation = 0L
    private var switching = false
    private var ready = false
    private var acceptingFrames = true
    private var invalidatedDuringSwitch = false
    @Synchronized fun beginSwitch(): Long? {
        if (switching || generation == Long.MAX_VALUE) return null
        generation++
        switching = true
        ready = false
        invalidatedDuringSwitch = false
        return generation
    }
    @Synchronized fun finishSwitch(token: Long, success: Boolean): Boolean {
        if (!switching || generation != token) return false
        switching = false
        acceptingFrames = success && !invalidatedDuringSwitch
        ready = false // Only a later camera frame can restore preview readiness.
        return true
    }
    @Synchronized fun frame() { if (!switching && acceptingFrames) ready = true }
    // An uncorrelated opening event cannot undo a prior error.
    @Synchronized fun opening() { ready = false }
    @Synchronized fun invalidate() { ready = false; acceptingFrames = false; if (switching) invalidatedDuringSwitch = true }
    @Synchronized fun isReady(): Boolean = ready && !switching
}
