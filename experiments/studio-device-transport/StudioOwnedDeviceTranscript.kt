package de.kontrakurs.kontraprompter.cloud

/** Isolated v2 wire encoding; no enrollment, network calls or key storage. */
internal object StudioOwnedDeviceTranscript {
    private val hash = Regex("^[0-9a-f]{64}$")

    fun enrollment(deviceId: String, challengeId: String, nonce: String, publicKeyDer: ByteArray, scope: List<String>, ownerHash: String, workspaceHash: String, confirmingSessionHash: String): ByteArray {
        require(workspaceHash.matches(hash) && confirmingSessionHash.matches(hash))
        val validated = StudioDeviceTranscript.enrollment(deviceId, challengeId, nonce, publicKeyDer, scope, ownerHash).toString(Charsets.UTF_8)
        check(validated.startsWith("videomaker-studio-device-auth/enroll/v1\n"))
        return (validated.replaceFirst("videomaker-studio-device-auth/enroll/v1\n", "videomaker-studio-device-auth/enroll/v2\n") + "$workspaceHash\n$confirmingSessionHash\n").toByteArray(Charsets.UTF_8)
    }

    fun login(deviceId: String, challengeId: String, challengeToken: String, publicKeyDer: ByteArray, scope: List<String>, ownerHash: String, workspaceHash: String): ByteArray {
        require(ownerHash.matches(hash) && workspaceHash.matches(hash))
        val validated = StudioDeviceTranscript.login(deviceId, challengeId, challengeToken, publicKeyDer, scope).toString(Charsets.UTF_8)
        check(validated.startsWith("videomaker-studio-device-auth/login/v1\n"))
        return (validated.replaceFirst("videomaker-studio-device-auth/login/v1\n", "videomaker-studio-device-auth/login/v2\n") + "$ownerHash\n$workspaceHash\n").toByteArray(Charsets.UTF_8)
    }
}
