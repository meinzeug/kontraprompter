package de.kontrakurs.kontraprompter

/** Reject malformed/out-of-range values instead of silently changing the user's setting. */
internal fun remotePrompterValue(type: String, value: Double?): Float? {
    val number = value ?: return null
    if (!number.isFinite()) return null
    val range = when (type) {
        "set_speed" -> 5.0..160.0
        "set_font_size" -> 20.0..100.0
        else -> return null
    }
    if (number !in range) return null
    return number.toFloat()
}
