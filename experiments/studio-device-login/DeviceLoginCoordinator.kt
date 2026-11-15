package de.kontrakurs.kontraprompter.cloud

import java.util.UUID

/** Test-only protocol shape. A real HTTPS adapter and server logout do not exist here. */
internal data class DeviceChallenge(val challengeId: String,val token: String,val expiresInMs: Long)
internal data class DeviceTicket(val token: String,val expiresInMs: Long)
internal interface DeviceLoginTransport {
    fun begin(deviceId: String): DeviceChallenge
    fun complete(deviceId: String, challengeToken: String, signature: ByteArray): DeviceTicket
}
internal fun interface DeviceLoginSigner { fun sign(challenge: DeviceChallenge): ByteArray }

/** All fields stay in memory. No HTTP, key creation, admin password or disk writes. */
internal class DeviceLoginCoordinator(private val monotonicMillis: () -> Long) {
    internal class Attempt internal constructor()
    internal class Session internal constructor(internal val token: String,internal val deadline: Long)
    private val lock=Any()
    private var pending: Attempt?=null
    private var session: Session?=null
    private var explicitlyLoggedOut=false
    private var clockFloor=-1L
    private var clockInvalid=false

    private fun now(): Long = synchronized(lock) {
        val value=runCatching { monotonicMillis() }.getOrDefault(-1L)
        if(value<0 || value<clockFloor){ clockInvalid=true;pending=null;session=null;return@synchronized -1L }
        clockFloor=value;value
    }
    private fun validChallenge(deviceId: String,challenge: DeviceChallenge): Boolean = runCatching {
        check(UUID.fromString(deviceId).toString()==deviceId)
        check(UUID.fromString(challenge.challengeId).toString()==challenge.challengeId)
        val parts=challenge.token.split('.')
        check(challenge.token.length<=200 && parts.size==4 && parts[0]==challenge.challengeId)
        check(parts[1].matches(Regex("^(0|[1-9][0-9]{0,15})$")) && (parts[1].toLongOrNull() ?: -1L) in 0..9007199254740991L)
        val b64=Regex("^[A-Za-z0-9_-]{42}[AEIMQUYcgkosw048]$")
        check(parts[2].matches(b64) && parts[3].matches(b64));true
    }.getOrDefault(false)

    /** Local only: does not claim to revoke anything already accepted by the server. */
    fun logout() = synchronized(lock) { pending=null;session=null;explicitlyLoggedOut=true }

    /** Fixture hook models a new explicitly approved device enrollment. */
    fun explicitEnrollmentConfirmed() = synchronized(lock) { pending=null;session=null;explicitlyLoggedOut=false }

    fun snapshot(): Session? = synchronized(lock) {
        val at=now()
        session?.takeIf { at>=0 && at<it.deadline } ?: run { session=null;null }
    }

    fun unauthorized(snapshot: Session): Boolean = synchronized(lock) {
        if(session !== snapshot) false else { session=null;true }
    }

    fun login(deviceId: String,transport: DeviceLoginTransport,signer: DeviceLoginSigner): Session? {
        if(!runCatching { UUID.fromString(deviceId).toString()==deviceId }.getOrDefault(false))return null
        val attempt=synchronized(lock) {
            if(explicitlyLoggedOut || clockInvalid)return null
            session=null;Attempt().also { pending=it }
        }
        fun current()=synchronized(lock) { pending === attempt && !explicitlyLoggedOut }
        try {
            val started=now();if(started<0)return null
            val challenge=transport.begin(deviceId)
            if(!current() || challenge.expiresInMs !in 1..60_000 || !validChallenge(deviceId,challenge))return null
            val challengeDeadline=runCatching { Math.addExact(started,challenge.expiresInMs) }.getOrNull() ?: return null
            val at=now();if(at<started || at>=challengeDeadline)return null
            val signature=signer.sign(challenge)
            if(signature.isEmpty() || signature.size>80 || !current())return null
            val sent=now();if(sent<at || sent>=challengeDeadline)return null
            val result=transport.complete(deviceId,challenge.token,signature)
            if(result.expiresInMs !in 1..300_000 || !result.token.matches(Regex("^[A-Za-z0-9_-]{42}[AEIMQUYcgkosw048]$")))return null
            val deadline=runCatching { Math.addExact(sent,result.expiresInMs) }.getOrNull() ?: return null
            return synchronized(lock) {
                val received=now()
                if(pending !== attempt || explicitlyLoggedOut || received<sent || received>=deadline)null
                else Session(result.token,deadline).also { session=it;pending=null }
            }
        } finally {
            synchronized(lock) { if(pending === attempt)pending=null }
        }
    }
}
