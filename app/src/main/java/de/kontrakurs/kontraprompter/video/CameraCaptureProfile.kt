package de.kontrakurs.kontraprompter.video

/** All experimental capture settings require both a debug build and explicit opt-in. */
internal data class CameraCaptureProfile(val raw: Boolean, val fixedFps: Boolean, val width: Int, val height: Int) {
    companion object {
        fun select(debug: Boolean, rawRequested: Boolean, fixedFpsRequested: Boolean): CameraCaptureProfile {
            val raw = debug && rawRequested
            return if (raw) CameraCaptureProfile(true, fixedFpsRequested, 1280, 720)
            else CameraCaptureProfile(false, false, 640, 480)
        }
        fun recorderBitrate(raw: Boolean): Int = if (raw) 3_800_000 else 1_800_000
    }
}
