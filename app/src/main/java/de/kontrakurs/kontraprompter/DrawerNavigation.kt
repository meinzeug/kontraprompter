package de.kontrakurs.kontraprompter

/** Only routes with no session or recording side effects belong in this drawer. */
internal enum class DrawerDestination(val route: String, val label: String) {
    START("roles", "Start"),
    SCRIPTS("scripts", "Skripte"),
    STUDIO("studio", "Videomaker"),
    RECORDINGS("recordings", "Aufnahmen"),
    HELP("help", "Hilfe & Aufbau"),
}

/** Upload/camera flows must not be unmounted by a new drawer navigation path. */
internal fun canShowDrawer(page: String, cameraHost: Boolean, uploadRunning: Boolean): Boolean =
    !cameraHost && !uploadRunning && page in setOf("roles", "scripts", "editor", "studio", "recordings", "settings", "calibration", "help")

internal fun DrawerDestination.isCurrent(page: String): Boolean =
    route == page || (this == DrawerDestination.SCRIPTS && page in setOf("editor", "settings", "calibration"))

/** A live client only disconnects when it has a non-pending terminal status. */
internal fun mayDisconnectRemote(ready: Boolean, pending: Boolean, phase: String): Boolean =
    ready && !pending && phase in setOf("idle", "ready", "partial")

internal fun mayStartCamera(sessionPresent: Boolean): Boolean = !sessionPresent

/** No authority by itself: the domain must still fence state-changing actions. */
internal data class RecoverySnapshot<T : Any, L, P>(val session: T, val generation: Long, val lan: L, val paired: P) {
    fun matches(current: T?, currentGeneration: Long, currentLan: L, currentPaired: P): Boolean =
        session === current && generation == currentGeneration && lan == currentLan && paired == currentPaired
}
