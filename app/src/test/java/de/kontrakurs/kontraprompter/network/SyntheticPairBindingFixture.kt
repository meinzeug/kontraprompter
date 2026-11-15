package de.kontrakurs.kontraprompter.network

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.UUID

/** Test-only model. The anchor stands in for an independent, non-rollbackable authority. */
internal data class SyntheticPairRecord(val pairId: String, val cameraPin: String, val prompterPin: String, val epoch: Long, val revoked: Boolean)
internal class SyntheticAnchor(initial: List<SyntheticPairRecord> = emptyList()) {
    private val records = initial.associateBy { it.pairId }.toMutableMap()
    @Synchronized fun current(id: String): SyntheticPairRecord? = records[id]
    @Synchronized fun create(record: SyntheticPairRecord): Boolean {
        if (records.containsKey(record.pairId)) return false
        records[record.pairId] = record; return true
    }
    @Synchronized fun revoke(id: String): SyntheticPairRecord? {
        val previous = records[id] ?: return null
        if (previous.revoked || previous.epoch == Long.MAX_VALUE) return null
        return previous.copy(epoch = previous.epoch + 1, revoked = true).also { records[id] = it }
    }
}

internal enum class SyntheticRole { CAMERA, PROMPTER }
internal data class SyntheticPins(val camera: String, val prompter: String)

/**
 * Models one in-process session coordinator; it has no Android or LAN integration.
 * Signatures prove key possession and transcript agreement, not human intent.
 * Each call to confirm must be gated by a real, role-specific user confirmation.
 */
internal class SyntheticPairBindingFixture(private val anchor: SyntheticAnchor) {
    internal class Attempt internal constructor(
        val pairId: String, val pins: SyntheticPins, internal val cameraKey: PublicKey,
        internal val prompterKey: PublicKey, internal val nonceHex: String,
    )
    private val random = SecureRandom()
    private var active: Attempt? = null
    private val confirmed = mutableSetOf<SyntheticRole>()

    @Synchronized fun begin(pairId: String, camera: ByteArray, prompter: ByteArray): Attempt {
        require(runCatching { UUID.fromString(pairId).toString() == pairId }.getOrDefault(false))
        require(anchor.current(pairId) == null) // revoked IDs must never be reactivated
        val a = parseP256(camera); val b = parseP256(prompter)
        val pins = SyntheticPins(pin(a), pin(b)); require(pins.camera != pins.prompter)
        confirmed.clear()
        val nonceHex = ByteArray(32).also(random::nextBytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        return Attempt(pairId, pins, a, b, nonceHex).also { active = it }
    }

    @Synchronized fun message(attempt: Attempt, role: SyntheticRole): ByteArray? =
        if (active !== attempt) null else transcript(attempt, role)

    @Synchronized fun confirm(attempt: Attempt, role: SyntheticRole, displayed: SyntheticPins, userApproved: Boolean, proof: ByteArray): Boolean {
        if (active !== attempt || !userApproved || displayed != attempt.pins || role in confirmed) return false
        val publicKey = if (role == SyntheticRole.CAMERA) attempt.cameraKey else attempt.prompterKey
        val verified = runCatching { Signature.getInstance("SHA256withECDSA").run { initVerify(publicKey); update(transcript(attempt, role)); verify(proof) } }.getOrDefault(false)
        if (verified) confirmed.add(role)
        return verified
    }

    /** The same lock serializes final acceptance with disconnect and local revocation. */
    @Synchronized fun commit(attempt: Attempt): SyntheticPairRecord? {
        if (active !== attempt || confirmed != SyntheticRole.entries.toSet()) return null
        val record = SyntheticPairRecord(attempt.pairId, attempt.pins.camera, attempt.pins.prompter, 1, false)
        if (!anchor.create(record)) return null
        active = null
        return record
    }

    @Synchronized fun disconnect() { active = null }
    @Synchronized fun revoke(id: String): SyntheticPairRecord? {
        if (active?.pairId == id) active = null
        return anchor.revoke(id)
    }
    /** Snapshot alone is never authority. This model assumes its independent anchor is current. */
    @Synchronized fun permits(snapshot: SyntheticPairRecord): Boolean =
        !snapshot.revoked && anchor.current(snapshot.pairId) == snapshot

    private fun transcript(attempt: Attempt, role: SyntheticRole): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { out ->
            fun field(value: ByteArray) { out.writeInt(value.size); out.write(value) }
            field("kontraprompter-pairing-test/v1".toByteArray(Charsets.UTF_8))
            field(attempt.pairId.toByteArray(Charsets.US_ASCII))
            field(role.name.toByteArray(Charsets.US_ASCII))
            field(attempt.cameraKey.encoded); field(attempt.prompterKey.encoded); field(attempt.nonceHex.toByteArray(Charsets.US_ASCII))
        }
        bytes.toByteArray()
    }
}

private fun parseP256(encoded: ByteArray): PublicKey {
    require(encoded.size in 1..256)
    val input = encoded.copyOf()
    val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(input)) as? ECPublicKey ?: error("EC required")
    val expected = AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }.getParameterSpec(ECParameterSpec::class.java)
    val p = key.params
    require(p.curve == expected.curve && p.generator == expected.generator && p.order == expected.order && p.cofactor == expected.cofactor)
    require(key.encoded.contentEquals(input))
    return key
}
private fun pin(key: PublicKey): String = MessageDigest.getInstance("SHA-256").digest(key.encoded).joinToString("") { "%02x".format(it.toInt() and 255) }
