package de.kontrakurs.kontraprompter.cloud

import java.security.MessageDigest
import java.util.UUID

/** Isolated v1 wire encoding only. It does not enroll, authenticate or store keys. */
internal object StudioDeviceTranscript {
    private val allowed = setOf("projects:read", "recordings:upload")
    private val b64 = Regex("^[A-Za-z0-9_-]{42}[AEIMQUYcgkosw048]$")
    private val hex = Regex("^[0-9a-f]{64}$")
    private val decimal = Regex("^(0|[1-9][0-9]{0,15})$")
    private const val MAX_SAFE_INTEGER = 9007199254740991L

    private fun uuid(value: String): String {
        require(UUID.fromString(value).toString() == value)
        return value
    }

    private fun digest(publicKeyDer: ByteArray): String {
        // The future caller must supply the verified canonical P-256 public
        // key corresponding to its own Keystore private key.
        require(publicKeyDer.isNotEmpty() && publicKeyDer.size <= 256)
        return MessageDigest.getInstance("SHA-256").digest(publicKeyDer).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun capabilities(values: List<String>): String {
        require(values.isNotEmpty() && values.size <= allowed.size && values.toSet().size == values.size && values.all { it in allowed })
        return values.sorted().joinToString(",")
    }

    /** The owner hash comes from an authenticated enrollment adapter and explicit confirmation. */
    fun enrollment(deviceId: String, challengeId: String, nonce: String, publicKeyDer: ByteArray, scope: List<String>, ownerHash: String): ByteArray {
        uuid(deviceId); uuid(challengeId)
        require(nonce.matches(b64) && ownerHash.matches(hex))
        return "videomaker-studio-device-auth/enroll/v1\n$deviceId\n$challengeId\n$nonce\n${digest(publicKeyDer)}\n${capabilities(scope)}\n$ownerHash\n".toByteArray(Charsets.UTF_8)
    }

    fun login(deviceId: String, challengeId: String, challengeToken: String, publicKeyDer: ByteArray, scope: List<String>): ByteArray {
        uuid(deviceId); uuid(challengeId)
        require(challengeToken.length <= 200)
        val parts = challengeToken.split('.')
        require(parts.size == 4 && parts[0] == challengeId && parts[1].matches(decimal))
        require((parts[1].toLongOrNull() ?: -1L) in 0..MAX_SAFE_INTEGER && parts[2].matches(b64) && parts[3].matches(b64))
        return "videomaker-studio-device-auth/login/v1\n$deviceId\n$challengeToken\n${digest(publicKeyDer)}\n${capabilities(scope)}\n".toByteArray(Charsets.UTF_8)
    }
}
