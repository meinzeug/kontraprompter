package de.kontrakurs.kontraprompter.video

/** Control of the existing recorder; admission and recording identity belong to LiveSession. */
internal interface RecordingControl {
    fun pause(): Boolean
    fun resume(): Boolean
    fun stop(): Boolean
    fun abort(message: String)
}
