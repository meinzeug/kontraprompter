package de.kontrakurs.kontraprompter.navigation

/** Experimental model only; not wired to any UI or Android resource. */
internal enum class Destination { ROLES, SCRIPTS, EDITOR, SETTINGS, CALIBRATION, PROMPTER, CONNECTION, RECORDINGS, UPLOAD, STUDIO, HELP }
internal enum class SessionRole { NONE, CAMERA, PROMPTER, UNKNOWN }
internal enum class Take { NONE, ACTIVE, UNKNOWN }
internal enum class Upload { NONE, RUNNING, CANCELLING, UNCERTAIN, TERMINAL, UNKNOWN }
internal enum class UploadOwner { NONE, UPLOAD_SCREEN, ACTIVITY, UNKNOWN }
internal enum class NewRole { CAMERA, PROMPTER }

/** Epoch must increase for every ownership, session, recorder or upload change. */
internal data class NavigationSnapshot(
    val epoch: Long,
    val session: SessionRole,
    val localTake: Take,
    val remoteTake: Take,
    val upload: Upload,
    val uploadOwner: UploadOwner,
) {
    companion object {
        /** A restored process has no authority to assume resources were cleanly stopped. */
        fun restoredUnknown() = NavigationSnapshot(0, SessionRole.UNKNOWN, Take.UNKNOWN, Take.UNKNOWN, Upload.UNKNOWN, UploadOwner.UNKNOWN)
    }
}

internal class NavigationStack private constructor(private val entries: List<Destination>) {
    val current: Destination get() = entries.last()
    val previous: Destination? get() = entries.getOrNull(entries.lastIndex - 1)
    fun asList(): List<Destination> = entries.toList()
    internal fun push(page: Destination) = if (page == current) this else NavigationStack(entries + page)
    internal fun pop() = if (entries.size > 1) NavigationStack(entries.dropLast(1)) else this
    override fun equals(other: Any?) = other is NavigationStack && entries == other.entries
    override fun hashCode() = entries.hashCode()
    companion object { fun start() = NavigationStack(listOf(Destination.ROLES)) }
}

internal sealed interface NavigationRequest {
    data class Open(val destination: Destination) : NavigationRequest
    /** Models role switching separately: starting either role can replace the old session. */
    data class SwitchRole(val role: NewRole) : NavigationRequest
    data object Back : NavigationRequest
    data object Exit : NavigationRequest
}

internal enum class NavigationBlock {
    UPLOAD_RUNNING_OR_CANCELLING,
    UPLOAD_STATE_OR_OWNER_UNKNOWN,
    LOCAL_TAKE_ACTIVE_OR_UNKNOWN,
    REMOTE_TAKE_ACTIVE_OR_UNKNOWN,
    SESSION_MUST_CLOSE,
    SESSION_STATE_UNKNOWN,
}
internal enum class SessionEffect { KEEP, CLOSE_OR_REPLACE }

internal sealed interface NavigationDecision {
    data class Blocked(val reasons: Set<NavigationBlock>) : NavigationDecision
    class Planned internal constructor(
        val before: NavigationStack,
        val expected: NavigationSnapshot,
        val after: NavigationStack,
        val exit: Boolean,
        val sessionEffect: SessionEffect,
    ) : NavigationDecision
}

/** A plan is only a model: no method here closes, opens, records or cancels anything. */
internal object NavigationPolicy {
    fun plan(stack: NavigationStack, request: NavigationRequest, snapshot: NavigationSnapshot): NavigationDecision {
        val current = stack.current
        val leaving = when (request) {
            is NavigationRequest.Open -> request.destination != current
            is NavigationRequest.SwitchRole, NavigationRequest.Back, NavigationRequest.Exit -> true
        }
        val exiting = request == NavigationRequest.Exit || (request == NavigationRequest.Back && stack.previous == null)
        // Role switches are resource operations even when invoked away from the connection page.
        val closeBoundary = exiting || request is NavigationRequest.SwitchRole ||
            (request == NavigationRequest.Back && current == Destination.CONNECTION) ||
            (request is NavigationRequest.Open && leaving && current == Destination.CONNECTION &&
                (snapshot.session in setOf(SessionRole.CAMERA, SessionRole.UNKNOWN) || snapshot.localTake != Take.NONE))
        val blocks = buildSet {
            if (leaving && snapshot.upload == Upload.UNKNOWN) add(NavigationBlock.UPLOAD_STATE_OR_OWNER_UNKNOWN)
            if (leaving && snapshot.upload in setOf(Upload.RUNNING, Upload.CANCELLING)) {
                when {
                    exiting -> add(NavigationBlock.UPLOAD_RUNNING_OR_CANCELLING)
                    snapshot.uploadOwner == UploadOwner.ACTIVITY -> Unit // Only if a future integration verifies real ownership.
                    snapshot.uploadOwner == UploadOwner.UPLOAD_SCREEN && current == Destination.UPLOAD -> add(NavigationBlock.UPLOAD_RUNNING_OR_CANCELLING)
                    else -> add(NavigationBlock.UPLOAD_STATE_OR_OWNER_UNKNOWN)
                }
            }
            if (closeBoundary) {
                if (snapshot.localTake != Take.NONE) add(NavigationBlock.LOCAL_TAKE_ACTIVE_OR_UNKNOWN)
                if (snapshot.remoteTake != Take.NONE) add(NavigationBlock.REMOTE_TAKE_ACTIVE_OR_UNKNOWN)
                if (snapshot.session == SessionRole.UNKNOWN) add(NavigationBlock.SESSION_STATE_UNKNOWN)
                else if (snapshot.session != SessionRole.NONE) add(NavigationBlock.SESSION_MUST_CLOSE)
            }
        }
        if (blocks.isNotEmpty()) return NavigationDecision.Blocked(blocks)
        val after = when (request) {
            is NavigationRequest.Open -> stack.push(request.destination)
            is NavigationRequest.SwitchRole -> stack.push(if (request.role == NewRole.CAMERA) Destination.CONNECTION else Destination.SCRIPTS)
            NavigationRequest.Back -> stack.pop()
            NavigationRequest.Exit -> stack
        }
        return NavigationDecision.Planned(stack, snapshot, after, exiting, if (closeBoundary) SessionEffect.CLOSE_OR_REPLACE else SessionEffect.KEEP)
    }
}

/**
 * Model fence: snapshot publication, plan, cancel and stack commit share one lock.
 * This cannot fence resources that do not themselves use this lock; production
 * integration must enforce the same ordering in actual domain owners.
 */
internal class NavigationFence(initial: NavigationSnapshot = NavigationSnapshot.restoredUnknown()) {
    private val lock = Any()
    private var snapshot = initial
    private var stack = NavigationStack.start()
    private var pending: Ticket? = null
    private var exited = false
    internal class Ticket internal constructor(internal val plan: NavigationDecision.Planned)
    internal sealed interface PrepareResult {
        data class Blocked(val reasons: Set<NavigationBlock>) : PrepareResult
        class Ready internal constructor(val ticket: Ticket) : PrepareResult
        data object AlreadyExited : PrepareResult
    }

    /** Reject late/replayed source observations. All sources must share this epoch authority. */
    fun publish(next: NavigationSnapshot): Boolean = synchronized(lock) {
        if (exited || next.epoch <= snapshot.epoch) return@synchronized false
        snapshot = next
        pending = null
        true
    }

    fun prepare(request: NavigationRequest): PrepareResult = synchronized(lock) {
        if (exited) return@synchronized PrepareResult.AlreadyExited
        pending = null
        when (val decision = NavigationPolicy.plan(stack, request, snapshot)) {
            is NavigationDecision.Blocked -> PrepareResult.Blocked(decision.reasons)
            is NavigationDecision.Planned -> Ticket(decision).let { pending = it; PrepareResult.Ready(it) }
        }
    }

    fun cancel(ticket: Ticket): Boolean = synchronized(lock) {
        if (pending !== ticket) return@synchronized false
        pending = null
        true
    }

    /** Atomically checks the current model and consumes the ticket once. */
    fun commit(ticket: Ticket): Boolean = synchronized(lock) {
        if (exited || pending !== ticket || ticket.plan.before != stack || ticket.plan.expected != snapshot) return@synchronized false
        pending = null
        stack = ticket.plan.after
        exited = ticket.plan.exit
        true
    }

    fun current(): NavigationStack = synchronized(lock) { stack }
}
