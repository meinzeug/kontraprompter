package de.kontrakurs.kontraprompter.network

import kotlinx.coroutines.flow.StateFlow

/**
 * Signaling used by the camera, WebRTC and paired controls. A port may mark a
 * session ready only after the peer has been authenticated and approved.
 * Implementations must close/fail on loss of that authentication.
 */
interface SignalingPort {
    val state: StateFlow<LanState>
    fun send(type: String, payload: String): Boolean
    fun report(status: String, error: String? = null)
    fun reportFirstCameraFrame()
}
