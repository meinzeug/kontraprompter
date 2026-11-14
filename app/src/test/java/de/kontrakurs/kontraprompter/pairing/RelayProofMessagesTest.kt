package de.kontrakurs.kontraprompter.pairing

import org.junit.Assert.*
import org.junit.Test

class RelayProofMessagesTest {
    private val owner = "a".repeat(64)
    private val session = "b".repeat(64)
    private val transcript = "c".repeat(64)
    private val pair = "00000000-0000-4000-8000-000000000001"
    private val challenge = "00000000-0000-4000-8000-000000000002"
    private val camera = "00000000-0000-4000-8000-000000000003"
    private val prompter = "00000000-0000-4000-8000-000000000004"
    private val nonce = "A".repeat(43)
    private val room = "A".repeat(22)

    @Test fun `pairing transcript matches the server wire format and role binding`() {
        val actual = relayPairingProofMessage(owner,pair,challenge,nonce,transcript,camera,"camera-key".toByteArray(),prompter,"prompter-key".toByteArray(),RelayRole.CAMERA).toString(Charsets.UTF_8)
        val expected = "videomaker-relay-pair/v1\n$owner\n$pair\n$challenge\n$nonce\n$transcript\n$camera\n1da57ee8fb4654e9343dffaa2f1925e7158a3a07dd61bb2fa411741a5097f664\n$prompter\nff6d15f7d14324b2dac093e20f6b5562de82494d758ffa1890ab486b1e3b4b62\ncamera\n"
        assertEquals(expected, actual)
        assertNotEquals(actual, relayPairingProofMessage(owner,pair,challenge,nonce,transcript,camera,"camera-key".toByteArray(),prompter,"prompter-key".toByteArray(),RelayRole.PROMPTER).toString(Charsets.UTF_8))
    }
    @Test fun `reconnect proof binds account session room device role and credential hash`() {
        val tokenHash = "d".repeat(64)
        val expected = "videomaker-relay-session/v1\n$owner\n$session\n$pair\nprompter\n$prompter\n$room\n$tokenHash\n$challenge\n$nonce\n"
        assertEquals(expected,relaySessionProofMessage(owner,session,pair,RelayRole.PROMPTER,prompter,room,tokenHash,challenge,nonce).toString(Charsets.UTF_8))
        assertThrows(IllegalArgumentException::class.java) { relaySessionProofMessage(owner,session,pair,RelayRole.PROMPTER,prompter,"bad",tokenHash,challenge,nonce) }
        assertThrows(IllegalArgumentException::class.java) { relayPairingProofMessage(owner,pair,challenge,nonce,transcript,camera,byteArrayOf(1),camera,byteArrayOf(2),RelayRole.CAMERA) }
    }
    @Test fun `device reconnect proof matches server fixed vector`() {
        val device = "11111111-1111-4111-8111-111111111111"
        val pairId = "22222222-2222-4222-8222-222222222222"
        val attemptId = "33333333-3333-4333-8333-333333333333"
        val proof = deviceReconnectProofMessage("a".repeat(64), device, pairId, RelayRole.CAMERA, attemptId, "b".repeat(64), "c".repeat(64), 42)
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(proof).joinToString("") { "%02x".format(it.toInt() and 255) }
        assertEquals(420, proof.size)
        assertEquals("b86d384caa51c46969ae42c62e72544471eab52af6aad252f658863c1d580432", digest)
        assertThrows(IllegalArgumentException::class.java) { deviceReconnectProofMessage(owner, device, pairId, RelayRole.CAMERA, attemptId, "bad", "c".repeat(64), 42) }
        assertThrows(IllegalArgumentException::class.java) { deviceReconnectProofMessage(owner, device, pairId, RelayRole.CAMERA, attemptId, "b".repeat(64), "c".repeat(64), -1) }
    }

    @Test fun `device request proof matches server fixed vector and allowlist`() {
        val device = "11111111-1111-4111-8111-111111111111"
        val pairId = "22222222-2222-4222-8222-222222222222"
        val bodyHash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        val proof = deviceRequestProofMessage("a".repeat(64), device, pairId, RelayRole.CAMERA, "d".repeat(64), "e".repeat(64), "POST", "/api/turn/credentials", bodyHash)
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(proof).joinToString("") { "%02x".format(it.toInt() and 255) }
        assertEquals(460, proof.size)
        assertEquals("aeb987e89d30b5d1a5d02ed59fa8a0942a5b37c13c89e813d468c3755cbb4adb", digest)
        assertThrows(IllegalArgumentException::class.java) { deviceRequestProofMessage(owner, device, pairId, RelayRole.CAMERA, "d".repeat(64), "e".repeat(64), "DELETE", "/api/turn/credentials", bodyHash) }
        assertThrows(IllegalArgumentException::class.java) { deviceRequestProofMessage(owner, device, pairId, RelayRole.CAMERA, "d".repeat(64), "e".repeat(64), "POST", "/api/relay/connect", bodyHash) }
    }

}
