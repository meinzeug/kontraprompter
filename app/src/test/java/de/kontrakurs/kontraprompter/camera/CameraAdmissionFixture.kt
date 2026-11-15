package de.kontrakurs.kontraprompter.camera

/** Test-only state model. It does not change the Android protocol or recorder. */
internal enum class SyntheticAdmissionPhase { PREPARED, DISPATCHING, ADMITTED, EXECUTED, REJECTED, UNCERTAIN }
internal data class SyntheticAdmissionCommand(val id: String, val action: String, val recordingId: String, val projectId: String)
internal data class SyntheticAdmissionEntry(val command: SyntheticAdmissionCommand, val phase: SyntheticAdmissionPhase)

/** Synthetic durable store; failBefore/failAfter model a known or uncertain commit outcome. */
internal class SyntheticAdmissionStore(initial: List<SyntheticAdmissionEntry> = emptyList()) {
    private val entries = initial.associateBy { it.command.id }.toMutableMap()
    var failBefore = false
    var failAfter = false
    @Synchronized fun read(id: String) = entries[id]
    @Synchronized fun all() = entries.values.toList()
    @Synchronized fun write(entry: SyntheticAdmissionEntry) {
        if (failBefore) error("injected error before commit")
        entries[entry.command.id] = entry
        if (failAfter) error("injected uncertain commit")
    }
}

/**
 * A synthetic owner and journal. `admit` must atomically check the current
 * recorder, project and ownership inside its own lock; the real adapter does
 * not exist. No user I/O occurs while the model's state lock is held.
 */
internal class CameraAdmissionFixture(private val store: SyntheticAdmissionStore) {
    private val guard = Any()
    private var broken = false

    fun prepare(command: SyntheticAdmissionCommand): SyntheticAdmissionPhase = synchronized(guard) {
        check(!broken); require(command.action in setOf("pause", "resume"))
        store.read(command.id)?.let { old ->
            require(old.command == command) { "command id reused" }
            return@synchronized old.phase
        }
        persist(SyntheticAdmissionEntry(command, SyntheticAdmissionPhase.PREPARED))
        SyntheticAdmissionPhase.PREPARED
    }

    fun dispatch(id: String, admit: (SyntheticAdmissionCommand) -> Boolean): SyntheticAdmissionPhase {
        val command = synchronized(guard) {
            check(!broken)
            val entry = checkNotNull(store.read(id))
            if (entry.phase != SyntheticAdmissionPhase.PREPARED) return entry.phase
            persist(entry.copy(phase = SyntheticAdmissionPhase.DISPATCHING))
            entry.command
        }
        val phase = try {
            if (admit(command)) SyntheticAdmissionPhase.ADMITTED else SyntheticAdmissionPhase.REJECTED
        } catch (_: Exception) { SyntheticAdmissionPhase.UNCERTAIN }
        return synchronized(guard) {
            check(!broken)
            val entry = checkNotNull(store.read(id)); check(entry.command == command && entry.phase == SyntheticAdmissionPhase.DISPATCHING)
            persist(entry.copy(phase = phase)); phase
        }
    }

    /** Status is trustworthy only if correlated with this exact original command. */
    fun observed(id: String, recordingId: String, projectId: String, desired: Boolean): Boolean = synchronized(guard) {
        check(!broken); val entry = store.read(id) ?: return false
        if (entry.phase != SyntheticAdmissionPhase.ADMITTED || entry.command.recordingId != recordingId || entry.command.projectId != projectId || !desired) return false
        persist(entry.copy(phase = SyntheticAdmissionPhase.EXECUTED)); true
    }

    /** These are model acknowledgements, not supported by the existing server wire protocol. */
    fun ack(id: String): String? = synchronized(guard) {
        if (broken) return null
        when (store.read(id)?.phase) {
            SyntheticAdmissionPhase.ADMITTED, SyntheticAdmissionPhase.EXECUTED -> "accepted"
            SyntheticAdmissionPhase.REJECTED -> "rejected"
            else -> null
        }
    }

    /** After process loss, unresolved decisions cannot be silently retried. */
    fun recover(): Unit = synchronized(guard) {
        check(!broken)
        store.all().filter { it.phase in setOf(SyntheticAdmissionPhase.PREPARED, SyntheticAdmissionPhase.DISPATCHING, SyntheticAdmissionPhase.ADMITTED) }
            .forEach { persist(it.copy(phase = SyntheticAdmissionPhase.UNCERTAIN)) }
    }

    private fun persist(entry: SyntheticAdmissionEntry) {
        try { store.write(entry) } catch (error: Exception) { broken = true; throw error }
    }
}
