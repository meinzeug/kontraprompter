package de.kontrakurs.kontraprompter

/** Only routes with no session or recording side effects belong in this drawer. */
internal enum class DrawerDestination(val route: String, val label: String) {
    START("roles", "Start"),
    SCRIPTS("scripts", "Skripte"),
    STUDIO("studio", "Videomaker"),
    RECORDINGS("recordings", "Aufnahmen"),
    SETTINGS("settings", "Einstellungen"),
    HELP("help", "Info & Hilfe"),
}

/** Upload/camera flows must not be unmounted by a new drawer navigation path. */
internal fun canShowDrawer(page: String, cameraHost: Boolean, uploadRunning: Boolean): Boolean =
    !cameraHost && !uploadRunning && page in setOf("roles", "scripts", "editor", "studio", "recordings", "settings", "calibration", "help")

internal fun DrawerDestination.isCurrent(page: String): Boolean =
    route == page || (this == DrawerDestination.SCRIPTS && page in setOf("editor", "calibration"))

/** A live client only disconnects when it has a non-pending terminal status. */
internal fun mayDisconnectRemote(ready: Boolean, pending: Boolean, phase: String): Boolean =
    ready && !pending && phase in setOf("idle", "ready", "partial")

internal fun mayStartCamera(sessionPresent: Boolean): Boolean = !sessionPresent

/** No authority by itself: the domain must still fence state-changing actions. */
internal data class RecoverySnapshot<T : Any, L, P>(val session: T, val generation: Long, val lan: L, val paired: P) {
    fun matches(current: T?, currentGeneration: Long, currentLan: L, currentPaired: P): Boolean =
        session === current && generation == currentGeneration && lan == currentLan && paired == currentPaired
}

/** Avoid mutual help/settings return loops when moving directly between them. */
internal fun drawerReturnOrigin(target: DrawerDestination, current: String, helpReturn: String, settingsReturn: String): String {
    val candidate = when {
        target == DrawerDestination.HELP && current == "settings" -> settingsReturn
        target == DrawerDestination.SETTINGS && current == "help" -> helpReturn
        else -> current
    }
    return candidate.takeIf { it in setOf("roles", "scripts", "editor", "studio", "recordings", "calibration") } ?: "roles"
}
