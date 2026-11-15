package de.kontrakurs.kontraprompter

/** Keep the original layout when width or accessibility settings cannot safely fit all controls. */
internal fun useWidePrompterLayout(widthDp: Float, heightDp: Float, fontScale: Float, hasKeyboardRemote: Boolean): Boolean =
    widthDp.isFinite() && heightDp.isFinite() && fontScale.isFinite() &&
        widthDp >= 720f && heightDp in 1f..500f && widthDp > heightDp &&
        fontScale in 0.5f..1.1f && !hasKeyboardRemote
