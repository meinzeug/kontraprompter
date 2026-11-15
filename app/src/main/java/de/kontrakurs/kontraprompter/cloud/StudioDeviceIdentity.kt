package de.kontrakurs.kontraprompter.cloud

import java.security.MessageDigest
import java.security.Signature
import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec
import java.util.UUID

internal data class StudioIdentityMarker(val id: String, val state: String, val fingerprint: String?)
internal interface StudioIdentityKey {
    val publicKeyDer: ByteArray
    val nonExportable: Boolean
    fun sign(message: ByteArray): ByteArray
}
internal interface StudioIdentityStore {
    fun marker(): StudioIdentityMarker?
    fun hasAnyDeviceKey(): Boolean
    fun key(id: String): StudioIdentityKey?
    fun createKey(id: String): StudioIdentityKey
    fun write(marker: StudioIdentityMarker)
}

internal sealed interface StudioIdentityState {
    data object EnrollmentRequired : StudioIdentityState
    data object RecoveryRequired : StudioIdentityState
    data class Ready(val id: String, val publicKeyDer: ByteArray) : StudioIdentityState
}

/** Inactive building block. No server access, automatic enrollment, reset or key deletion. */
internal class StudioDeviceIdentity(private val store: StudioIdentityStore) {
    companion object { private val processLock = Any() }

    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    private fun validId(id: String) = runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)

    /** This read never generates a key, writes a marker or silently heals a reset. */
    fun load(): StudioIdentityState = synchronized(processLock) {
        runCatching {
            val marker = store.marker() ?: return@synchronized if (store.hasAnyDeviceKey()) StudioIdentityState.RecoveryRequired else StudioIdentityState.EnrollmentRequired
            if (!validId(marker.id) || marker.state != "ready" || marker.fingerprint?.matches(Regex("^[0-9a-f]{64}$")) != true)
                return@synchronized StudioIdentityState.RecoveryRequired
            val key = store.key(marker.id) ?: return@synchronized StudioIdentityState.RecoveryRequired
            val publicKey = key.publicKeyDer
            if (!key.nonExportable || publicKey.isEmpty() || publicKey.size > 256 || hash(publicKey) != marker.fingerprint)
                return@synchronized StudioIdentityState.RecoveryRequired
            StudioIdentityState.Ready(marker.id, publicKey.copyOf())
        }.getOrElse { StudioIdentityState.RecoveryRequired }
    }

    /** Call only after explicit user consent. An incomplete/old identity is never replaced. */
    fun createAfterExplicitConsent(): StudioIdentityState = synchronized(processLock) {
        if (load() != StudioIdentityState.EnrollmentRequired) return@synchronized StudioIdentityState.RecoveryRequired
        runCatching {
            val id = UUID.randomUUID().toString()
            store.write(StudioIdentityMarker(id, "creating", null))
            val key = store.createKey(id)
            val pub = key.publicKeyDer
            check(key.nonExportable && pub.isNotEmpty() && pub.size <= 256)
            store.write(StudioIdentityMarker(id, "ready", hash(pub)))
            load()
        }.getOrElse { StudioIdentityState.RecoveryRequired }
    }

    /** Test adapter only until a session/consent fence owns these calls. */
    fun signEnrollmentForFixture(expectedId: String, challengeId: String, nonce: String, scope: List<String>, ownerHash: String): ByteArray =
        sign(expectedId) { StudioDeviceTranscript.enrollment(expectedId,challengeId,nonce,it,scope,ownerHash) }

    fun signLoginForFixture(expectedId: String, challengeId: String, token: String, scope: List<String>): ByteArray =
        sign(expectedId) { StudioDeviceTranscript.login(expectedId,challengeId,token,it,scope) }

    /** V2 fixture only. The caller must fence consent and the eventual HTTP action. */
    fun signOwnedEnrollmentForFixture(expectedId: String, challengeId: String, nonce: String, scope: List<String>, ownerHash: String, workspaceHash: String, sessionHash: String): ByteArray =
        sign(expectedId) { StudioOwnedDeviceTranscript.enrollment(expectedId,challengeId,nonce,it,scope,ownerHash,workspaceHash,sessionHash) }

    fun signOwnedLoginForFixture(expectedId: String, challengeId: String, token: String, scope: List<String>, ownerHash: String, workspaceHash: String): ByteArray =
        sign(expectedId) { StudioOwnedDeviceTranscript.login(expectedId,challengeId,token,it,scope,ownerHash,workspaceHash) }

    private fun sign(expectedId: String, transcript: (ByteArray) -> ByteArray): ByteArray = synchronized(processLock) {
        val state = load() as? StudioIdentityState.Ready ?: error("Studio identity needs explicit recovery")
        check(state.id == expectedId)
        val key = store.key(state.id) ?: error("Studio identity disappeared")
        check(key.nonExportable && key.publicKeyDer.contentEquals(state.publicKeyDer))
        val message = transcript(state.publicKeyDer.copyOf())
        val signature = key.sign(message)
        val verifier = Signature.getInstance("SHA256withECDSA")
        verifier.initVerify(KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(state.publicKeyDer)))
        verifier.update(message)
        check(verifier.verify(signature))
        signature
    }
}
