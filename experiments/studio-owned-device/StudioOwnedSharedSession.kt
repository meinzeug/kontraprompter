package de.kontrakurs.kontraprompter.cloud

/** Fixture: both clients must share this instance; no HTTP or password storage. */
internal class StudioOwnedSharedSession(
    private val gate: StudioOwnedProofGate,
    private val store: StudioOwnedConsentStore,
) : AutoCloseable {
    internal class Lease internal constructor(internal val approval: StudioOwnedProofGate.Approval, internal val record: StudioOwnedConsentRecord)
    private val lock = Any()
    private var transition: Any = Any()
    private var lease: Lease? = null
    private var closed = false
    private var failed = false

    /** Keystore calls happen outside the lock; a stale approval never becomes current. */
    fun approveAfterExplicitConsent(deviceId: String, owner: String, workspace: String, sessionHash: String, scope: List<String>): Lease? {
        val attempt = synchronized(lock) {
            if (closed || failed) return null
            gate.invalidate();lease=null;Any().also { transition=it }
        }
        val approval = try { gate.approveAfterExplicitConsent(deviceId,owner,workspace,sessionHash,scope) }
            catch (_: IllegalArgumentException) { return null } catch (_: IllegalStateException) { return null } catch (_: SecurityException) { return null }
        return synchronized(lock) {
            if (closed || failed || transition !== attempt || !gate.isCurrent(approval)) return@synchronized null
            try {
                val old=store.read()
                if (old!=null && (old.revoked || old.deviceId!=deviceId || old.ownerHash!=owner || old.workspaceHash!=workspace || old.revision<1 || old.revision==Long.MAX_VALUE)) {
                    gate.invalidate();return@synchronized null
                }
                val next=StudioOwnedConsentRecord(deviceId,owner,workspace,(old?.revision ?: 0)+1,false)
                if (!store.compareAndSet(old,next)) { gate.invalidate();return@synchronized null }
                Lease(approval,next).also { lease=it }
            } catch (_: Exception) { failed=true;gate.invalidate();lease=null;null }
        }
    }

    /** Local only; false means the durable tombstone was not confirmed. */
    fun revokeLocal(): Boolean = synchronized(lock) {
        transition=Any();gate.invalidate();lease=null
        if(closed || failed)return@synchronized false
        try {
            val old=store.read() ?: return@synchronized true
            if(old.revoked)return@synchronized true
            if(old.revision<1 || old.revision==Long.MAX_VALUE){failed=true;return@synchronized false}
            val ok=store.compareAndSet(old,old.copy(revision=old.revision+1,revoked=true))
            if(!ok)failed=true
            ok
        } catch (_: Exception) { failed=true;false }
    }

    fun invalidateLocal() = synchronized(lock) { transition=Any();gate.invalidate();lease=null }
    override fun close() = synchronized(lock) { closed=true;transition=Any();gate.close();lease=null }

    fun isCurrent(value: Lease): Boolean = synchronized(lock) {
        if(closed || failed || lease !== value || !gate.isCurrent(value.approval))return@synchronized false
        try { if(store.read()==value.record)true else { gate.invalidate();lease=null;false } }
        catch (_: Exception) { failed=true;gate.invalidate();lease=null;false }
    }
    fun enrollment(value: Lease, challengeId: String, nonce: String): ByteArray? {
        if(!isCurrent(value))return null
        val signature=gate.enrollment(value.approval,challengeId,nonce) ?: return null
        return if(isCurrent(value))signature else null
    }
    fun login(value: Lease, challengeId: String, token: String): ByteArray? {
        if(!isCurrent(value))return null
        val signature=gate.login(value.approval,challengeId,token) ?: return null
        return if(isCurrent(value))signature else null
    }
}
