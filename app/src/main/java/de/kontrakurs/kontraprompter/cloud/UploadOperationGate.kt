package de.kontrakurs.kontraprompter.cloud

/** A cancelled coroutine cannot start another upload until the old worker exits. */
internal class UploadOperationGate {
    internal class Ticket internal constructor() {
        var workerStarted = false
        var workerDone = false
        var operationDone = false
        var progressStopped = false
    }
    private var owner: Ticket? = null
    @Synchronized fun acquire(): Ticket? {
        if (owner != null) return null
        return Ticket().also { owner = it }
    }
    @Synchronized fun workerStarted(ticket: Ticket) { check(owner === ticket && !ticket.operationDone); ticket.workerStarted = true }
    @Synchronized fun workerFinished(ticket: Ticket) { if (owner === ticket) { ticket.workerDone = true; releaseIfFinished(ticket) } }
    @Synchronized fun operationFinished(ticket: Ticket) { if (owner === ticket) { ticket.operationDone = true; releaseIfFinished(ticket) } }
    @Synchronized fun stopProgress(ticket: Ticket) { if (owner === ticket) ticket.progressStopped = true }
    @Synchronized fun deliverProgress(ticket: Ticket, deliver: () -> Unit) {
        if (owner === ticket && !ticket.operationDone && !ticket.progressStopped) deliver()
    }
    private fun releaseIfFinished(ticket: Ticket) {
        if (ticket.operationDone && (!ticket.workerStarted || ticket.workerDone)) owner = null
    }
}
