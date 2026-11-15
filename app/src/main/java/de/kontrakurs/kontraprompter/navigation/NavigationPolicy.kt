package de.kontrakurs.kontraprompter.navigation

/** Experimental, pure policy. Not wired to the Activity or to recorder/upload lifecycle. */
internal enum class Destination { ROLES, SCRIPTS, EDITOR, SETTINGS, CALIBRATION, PROMPTER, CONNECTION, RECORDINGS, UPLOAD, STUDIO, HELP }
internal enum class SessionRole { NONE, CAMERA, PROMPTER }
internal enum class Take { NONE, ACTIVE, UNKNOWN }
internal enum class Upload { NONE, RUNNING, CANCELLING, UNCERTAIN, TERMINAL }

/** Epoch is supplied by the future owner and must change on every relevant lifecycle transition. */
internal data class NavigationSnapshot(
    val epoch: Long,
    val session: SessionRole,
    val localTake: Take,
    val remoteTake: Take,
    val upload: Upload,
)

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
    data object Back : NavigationRequest
    data object Exit : NavigationRequest
}

internal enum class NavigationBlock {
    UPLOAD_RUNNING_OR_CANCELLING,
    LOCAL_TAKE_ACTIVE_OR_UNKNOWN,
    REMOTE_TAKE_ACTIVE_OR_UNKNOWN,
    SESSION_MUST_CLOSE,
}

internal sealed interface NavigationDecision {
    data class Blocked(val reasons: Set<NavigationBlock>) : NavigationDecision
    class Planned internal constructor(
        val before: NavigationStack,
        val expected: NavigationSnapshot,
        val after: NavigationStack,
        val exit: Boolean,
    ) : NavigationDecision
}

/** Only plans navigation; no method here closes a session, records, or cancels an upload. */
internal object NavigationPolicy {
    fun plan(stack: NavigationStack, request: NavigationRequest, snapshot: NavigationSnapshot): NavigationDecision {
        val current = stack.current
        val leaving = when (request) {
            is NavigationRequest.Open -> request.destination != current
            NavigationRequest.Back -> true
            NavigationRequest.Exit -> true
        }
        val exiting = request == NavigationRequest.Exit || (request == NavigationRequest.Back && stack.previous == null)
        // Back from the connection screen historically disconnects. Opening scripts on
        // the prompter preserves the paired session; a camera host must first close it.
        val closeBoundary = exiting || (request == NavigationRequest.Back && current == Destination.CONNECTION) ||
            (request is NavigationRequest.Open && leaving && current == Destination.CONNECTION &&
                (snapshot.session == SessionRole.CAMERA || snapshot.localTake != Take.NONE))
        val blocks = buildSet {
            if (leaving && (current == Destination.UPLOAD || exiting) && snapshot.upload in setOf(Upload.RUNNING, Upload.CANCELLING))
                add(NavigationBlock.UPLOAD_RUNNING_OR_CANCELLING)
            if (closeBoundary) {
                // A session reference can already be gone while a recorder is still finalizing.
                if (snapshot.localTake != Take.NONE) add(NavigationBlock.LOCAL_TAKE_ACTIVE_OR_UNKNOWN)
                if (snapshot.remoteTake != Take.NONE) add(NavigationBlock.REMOTE_TAKE_ACTIVE_OR_UNKNOWN)
                if (snapshot.session != SessionRole.NONE) add(NavigationBlock.SESSION_MUST_CLOSE)
            }
        }
        if (blocks.isNotEmpty()) return NavigationDecision.Blocked(blocks)
        val after = when (request) {
            is NavigationRequest.Open -> stack.push(request.destination)
            NavigationRequest.Back -> stack.pop()
            NavigationRequest.Exit -> stack
        }
        return NavigationDecision.Planned(stack, snapshot, after, exiting)
    }

    /** Caller must compare and apply under the same domain/lifecycle synchronization. */
    fun revalidate(plan: NavigationDecision.Planned, stack: NavigationStack, snapshot: NavigationSnapshot): Boolean =
        plan.before == stack && plan.expected == snapshot
}
