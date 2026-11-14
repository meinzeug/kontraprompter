package de.kontrakurs.kontraprompter.video

data class CameraSelection(val front: Boolean? = null, val canSwitch: Boolean = false, val switching: Boolean = false, val error: String? = null)
data class CameraSwitchResult(val success: Boolean, val front: Boolean?, val message: String?)

internal fun cameraSwitchAllowed(host: Boolean, closed: Boolean, disconnected: Boolean, remoteExclusive: Boolean, remoteOwner: Boolean, recording: Boolean, switching: Boolean, canSwitch: Boolean): Boolean =
    host && !closed && !disconnected && remoteExclusive == remoteOwner && !recording && !switching && canSwitch
