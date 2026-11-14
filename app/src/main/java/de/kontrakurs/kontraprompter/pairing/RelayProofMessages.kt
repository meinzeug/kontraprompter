package de.kontrakurs.kontraprompter.pairing

import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

internal enum class RelayRole(val wire: String) { CAMERA("camera"), PROMPTER("prompter") }
private fun canonicalUuid(value: String): Boolean = runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)
private fun digest(value: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(value).joinToString("") { "%02x".format(it.toInt() and 255) }
private fun encoded(value: String, bytes: Int): Boolean = runCatching {
    val decoded = Base64.getUrlDecoder().decode(value)
    decoded.size == bytes && Base64.getUrlEncoder().withoutPadding().encodeToString(decoded) == value
}.getOrDefault(false)
private fun hash(value: String) = value.matches(Regex("^[a-f0-9]{64}$"))

/** Byte-for-byte counterpart of Videomaker's domain-separated pairing proof. */
internal fun relayPairingProofMessage(owner: String, pairId: String, challengeId: String, nonce: String, transcriptHash: String, cameraId: String, cameraKey: ByteArray, prompterId: String, prompterKey: ByteArray, role: RelayRole): ByteArray {
    require(hash(owner) && canonicalUuid(pairId) && canonicalUuid(challengeId) && encoded(nonce, 32) && hash(transcriptHash))
    require(canonicalUuid(cameraId) && canonicalUuid(prompterId) && cameraId != prompterId)
    require(cameraKey.size in 1..256 && prompterKey.size in 1..256 && !cameraKey.contentEquals(prompterKey))
    return "videomaker-relay-pair/v1\n$owner\n$pairId\n$challengeId\n$nonce\n$transcriptHash\n$cameraId\n${digest(cameraKey)}\n$prompterId\n${digest(prompterKey)}\n${role.wire}\n".toByteArray(Charsets.UTF_8)
}

/** Every reconnect uses a new challenge and binds the proof to this role and room. */
internal fun relaySessionProofMessage(owner: String, sessionHash: String, pairId: String, role: RelayRole, deviceId: String, roomId: String, tokenHash: String, challengeId: String, nonce: String): ByteArray {
    require(hash(owner) && hash(sessionHash) && canonicalUuid(pairId) && canonicalUuid(deviceId))
    require(encoded(roomId, 16) && hash(tokenHash) && canonicalUuid(challengeId) && encoded(nonce, 32))
    return "videomaker-relay-session/v1\n$owner\n$sessionHash\n$pairId\n${role.wire}\n$deviceId\n$roomId\n$tokenHash\n$challengeId\n$nonce\n".toByteArray(Charsets.UTF_8)
}

/** Byte-for-byte device reconnect proof; origin and purpose are fixed, never server-supplied. */
internal fun deviceReconnectProofMessage(owner: String, deviceId: String, pairId: String, role: RelayRole, attemptId: String, clientChallenge: String, serverNonce: String, revision: Long): ByteArray {
    require(hash(owner) && canonicalUuid(deviceId) && canonicalUuid(pairId) && canonicalUuid(attemptId))
    require(hash(clientChallenge) && hash(serverNonce) && revision >= 0)
    val fields = listOf("videomaker-device-reconnect/v1", "https://videomaker.dorfspy.de", "reconnect", owner, deviceId, pairId, role.wire, attemptId, clientChallenge, serverNonce, revision.toString())
    val buffer = java.io.ByteArrayOutputStream()
    java.io.DataOutputStream(buffer).use { output ->
        for (field in fields) {
            val bytes = field.toByteArray(Charsets.UTF_8)
            output.writeInt(bytes.size); output.write(bytes)
        }
    }
    return buffer.toByteArray()
}

/** Fixed allowlist and actual body digest prevent signing an unrelated HTTP operation. */
internal fun deviceRequestProofMessage(owner: String, deviceId: String, pairId: String, role: RelayRole, sessionHash: String, requestNonce: String, method: String, path: String, bodySha256: String): ByteArray {
    require(hash(owner) && canonicalUuid(deviceId) && canonicalUuid(pairId))
    require(hash(sessionHash) && hash(requestNonce) && hash(bodySha256))
    require((method == "POST" && path == "/api/turn/credentials") || (method == "GET" && path == "/api/relay/connect"))
    val fields = listOf("videomaker-device-request/v1", "https://videomaker.dorfspy.de", owner, deviceId, pairId, role.wire, sessionHash, requestNonce, method, path, bodySha256)
    val buffer = java.io.ByteArrayOutputStream()
    java.io.DataOutputStream(buffer).use { output ->
        for (field in fields) {
            val bytes = field.toByteArray(Charsets.UTF_8)
            output.writeInt(bytes.size); output.write(bytes)
        }
    }
    return buffer.toByteArray()
}
