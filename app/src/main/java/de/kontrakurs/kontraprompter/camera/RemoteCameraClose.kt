package de.kontrakurs.kontraprompter.camera

/** Release ownership only after the recorder is stopped and the transport is drained. */
internal suspend fun finishCameraRemote(
    stop: suspend () -> Unit,
    drain: suspend () -> Unit,
    awaitRecorder: suspend () -> Unit,
    persist: suspend () -> Unit,
    release: () -> Unit,
) {
    stop()
    drain()
    awaitRecorder()
    var failure: Exception? = null
    try { persist() } catch (error: Exception) { failure = error }
    release()
    failure?.let { throw it }
}
