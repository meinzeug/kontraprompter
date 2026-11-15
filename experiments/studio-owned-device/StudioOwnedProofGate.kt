package de.kontrakurs.kontraprompter.cloud

import java.util.UUID
import java.util.Collections

/** In-memory fixture only; never starts networking or persists a grant. */
internal class StudioOwnedProofGate(private val identity: StudioDeviceIdentity) : AutoCloseable {
    internal class Approval internal constructor(
        val deviceId: String, val ownerHash: String, val workspaceHash: String,
        val sessionHash: String, val scope: List<String>,
    )
    private val lock = Any()
    private var approved: Approval? = null
    private var closed = false
    private var generation: Any = Any()
    private val digest = Regex("^[0-9a-f]{64}$")
    private val allowed = setOf("projects:read", "recordings:upload")

    /** Caller must establish identity and explicit consent via trusted UI first. */
    fun approveAfterExplicitConsent(deviceId: String, ownerHash: String, workspaceHash: String,
                                    sessionHash: String, scope: List<String>): Approval {
        val attempt = synchronized(lock) { check(!closed); generation }
        require(UUID.fromString(deviceId).toString() == deviceId)
        require(ownerHash.matches(digest) && workspaceHash.matches(digest) && sessionHash.matches(digest))
        val snapshot = Collections.unmodifiableList(ArrayList(scope))
        require(snapshot.isNotEmpty() && snapshot.size <= allowed.size && snapshot.toSet().size == snapshot.size && snapshot.all { it in allowed })
        // Keystore reads may block; never hold the lifecycle lock while doing them.
        val state = identity.load() as? StudioIdentityState.Ready ?: error("Identity needs recovery")
        check(state.id == deviceId)
        return synchronized(lock) {
            check(!closed && generation === attempt) { "Approval is no longer current" }
            generation = Any()
            Approval(deviceId,ownerHash,workspaceHash,sessionHash,snapshot).also { approved = it }
        }
    }

    fun invalidate() = synchronized(lock) { generation = Any(); approved = null }

    fun isCurrent(approval: Approval): Boolean = synchronized(lock) { !closed && approved === approval }
    override fun close() = synchronized(lock) { closed = true; generation = Any(); approved = null }

    fun enrollment(approval: Approval, challengeId: String, nonce: String): ByteArray? =
        sign(approval) { identity.signOwnedEnrollmentForFixture(approval.deviceId,challengeId,nonce,approval.scope,approval.ownerHash,approval.workspaceHash,approval.sessionHash) }

    fun login(approval: Approval, challengeId: String, token: String): ByteArray? =
        sign(approval) { identity.signOwnedLoginForFixture(approval.deviceId,challengeId,token,approval.scope,approval.ownerHash,approval.workspaceHash) }

    private fun sign(approval: Approval, operation: () -> ByteArray): ByteArray? {
        if (!isCurrent(approval)) return null
        val signature = try { operation() } catch (_: IllegalArgumentException) { return null }
            catch (_: IllegalStateException) { return null } catch (_: SecurityException) { return null }
        // The caller still needs an action fence around any later network send.
        return synchronized(lock) { if (!closed && approved === approval) signature else null }
    }
}
