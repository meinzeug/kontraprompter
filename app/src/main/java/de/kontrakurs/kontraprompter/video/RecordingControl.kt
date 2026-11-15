package de.kontrakurs.kontraprompter.video

/** Control of the existing recorder; admission and recording identity belong to LiveSession. */
internal interface RecordingControl {
    fun pause(): Boolean
    fun resume(): Boolean
    fun stop(): Boolean
    fun abort(message: String)
}

/** Compare inside LiveSession.guard with the project captured when the recorder was admitted. */
internal fun remoteProjectMatches(actual: String?, expected: String?): Boolean = expected != null && actual == expected
