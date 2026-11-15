package de.kontrakurs.kontraprompter.cloud

import java.security.MessageDigest

/** Fixture record: adapter must authenticate origin and durable enrollment before returning it. */
internal data class StudioOwnedRegistration(
    val origin: String, val deviceId: String, val publicKeyHash: String,
    val ownerHash: String, val workspaceHash: String, val revision: Long, val active: Boolean,
)
internal fun interface StudioOwnedRegistrationStore { fun read(): StudioOwnedRegistration? }

/** Explicitly disabled model; not registered with Application, UI, or networking. */
internal class StudioOwnedAutoRestore(
    private val identity: StudioDeviceIdentity,
    private val registrations: StudioOwnedRegistrationStore,
    private val login: DeviceLoginCoordinator,
    private val transport: DeviceLoginTransport,
) {
    companion object { private const val ORIGIN="https://videomaker.dorfspy.de" }
    private val lock=Any()
    private var generation:Any=Any()
    private var stopped=false
    private val hex=Regex("^[0-9a-f]{64}$")
    private val scope=listOf("projects:read","recordings:upload")
    private fun fingerprint(value:ByteArray)=MessageDigest.getInstance("SHA-256").digest(value).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    private fun valid(row:StudioOwnedRegistration?,state:StudioIdentityState):Boolean {
        if(row==null || row.origin!=ORIGIN || !row.active || row.revision<1 || !row.publicKeyHash.matches(hex) || !row.ownerHash.matches(hex) || !row.workspaceHash.matches(hex))return false
        val ready=state as? StudioIdentityState.Ready ?: return false
        return ready.id==row.deviceId && fingerprint(ready.publicKeyDer)==row.publicKeyHash
    }
    private fun same(attempt:Any,row:StudioOwnedRegistration):Boolean {
        val stillCurrent=synchronized(lock){!stopped && generation===attempt}
        if(!stillCurrent)return false
        return try{registrations.read()==row && valid(row,identity.load()) && synchronized(lock){!stopped && generation===attempt}}catch (_:Exception){false}
    }
    fun restore():DeviceLoginCoordinator.Session? {
        val attempt=synchronized(lock){if(stopped)return null;Any().also { generation=it }}
        val record=try{registrations.read()}catch (_:Exception){null} ?: return null
        if(!same(attempt,record))return null
        val signer=DeviceLoginSigner { challenge ->
            check(same(attempt,record))
            identity.signOwnedLoginForFixture(record.deviceId,challenge.challengeId,challenge.token,scope,record.ownerHash,record.workspaceHash)
        }
        val result=try{login.login(record.deviceId,transport,signer)}catch (_:Exception){null} ?: return null
        if(!same(attempt,record)){login.unauthorized(result);return null}
        return result
    }
    /** Local invalidation only; durable local and server revocation remain separate. */
    fun stop() { synchronized(lock){stopped=true;generation=Any()};login.logout() }
}
