package de.kontrakurs.kontraprompter.video

/** Remote commands require the remote owner and an exact recording identity. */
internal fun recordingControlAllowed(remoteExclusive: Boolean, remoteRequest: Boolean, activeId: String, requestedId: String?): Boolean =
    remoteExclusive == remoteRequest && (!remoteRequest || requestedId != null) && (requestedId == null || requestedId == activeId)
