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
}
