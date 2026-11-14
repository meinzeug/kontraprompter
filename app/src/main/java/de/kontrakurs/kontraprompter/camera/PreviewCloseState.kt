package de.kontrakurs.kontraprompter.camera

/** Access only under the owning runtime's lock. */
internal class PreviewCloseState {
    var id: String? = null
        private set
    private var completedMessage = "Webvorschau aus"

    fun begin(id: String, message: String) {
        check(this.id == null)
        this.id = id
        completedMessage = message
    }

    fun afterClose(message: String) { completedMessage = message }

    /** Failure deliberately stays blocked: the peer may still be transmitting. */
    fun complete(id: String, success: Boolean): String? {
        if (this.id != id) return null
        if (!success) return "Webvorschau: Trennung nicht bestätigt"
        this.id = null
        return completedMessage
    }
}
