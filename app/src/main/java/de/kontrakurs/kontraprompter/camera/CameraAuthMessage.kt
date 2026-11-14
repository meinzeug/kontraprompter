package de.kontrakurs.kontraprompter.camera

import java.util.UUID

/** Exact v2 wire representation; the server must hash and verify these same UTF-8 bytes. */
internal fun cameraAuthMessage(deviceId: String, challengeId: String, challenge: String, action: String, bodySha256: String): ByteArray {
    require(UUID.fromString(deviceId).toString() == deviceId && UUID.fromString(challengeId).toString() == challengeId)
    // 32 bytes encoded as unpadded base64url have exactly 43 characters, with four zero trailing bits.
    require(challenge.matches(Regex("^[A-Za-z0-9_-]{42}[AEIMQUYcgkosw048]$")))
    require(action == "register" || action == "session")
    require(bodySha256.matches(Regex("^[0-9a-f]{64}$")))
    return "videomaker-camera-device-auth/v2\n$deviceId\n$challengeId\n$challenge\n$action\n$bodySha256\n".toByteArray(Charsets.UTF_8)
}
