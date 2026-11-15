package de.kontrakurs.kontraprompter.video

/** Unknown codec capabilities cannot qualify an encoder for recording. */
internal inline fun <T : Any> supportsVideo(capabilities: T?, supports: (T) -> Boolean): Boolean =
    capabilities != null && supports(capabilities)
