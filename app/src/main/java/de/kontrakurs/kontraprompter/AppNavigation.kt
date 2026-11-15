package de.kontrakurs.kontraprompter

/** Pages with a live operation keep their existing explicit exit behavior. */
internal fun showAppMenu(page: String, cameraHost: Boolean): Boolean =
    !cameraHost && page !in setOf("connection", "prompter", "upload")

/** The settings index remembers where it was entered; detail pages return to the index. */
internal fun settingsBackPage(page: String, enteredFrom: String): String =
    if (page == "settings") enteredFrom else "settings"
