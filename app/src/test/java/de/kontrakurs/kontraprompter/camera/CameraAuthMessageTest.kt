package de.kontrakurs.kontraprompter.camera

import org.junit.Assert.*
import org.junit.Test
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec

class CameraAuthMessageTest {
    private val device = "90b9acc5-239f-4edc-978a-5f56080ac00b"
    private val challengeId = "3e139922-9fc4-46bd-b3e4-d9293c9b5940"
    private val nonce = "A".repeat(43)
    private val hash = "a".repeat(64)

    @Test fun `challenge message uses exact versioned utf8 bytes and terminal newline`() {
        val expected = "videomaker-camera-device-auth/v2\n$device\n$challengeId\n$nonce\nsession\n$hash\n"
        assertArrayEquals(expected.toByteArray(Charsets.UTF_8), cameraAuthMessage(device, challengeId, nonce, "session", hash))
    }

    @Test fun `ambiguous or injected fields are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { cameraAuthMessage(device.uppercase(), challengeId, nonce, "session", hash) }
        assertThrows(IllegalArgumentException::class.java) { cameraAuthMessage(device, challengeId, nonce.dropLast(1) + "B", "session", hash) }
        assertThrows(IllegalArgumentException::class.java) { cameraAuthMessage(device, challengeId, nonce, "session\nregister", hash) }
        assertThrows(IllegalArgumentException::class.java) { cameraAuthMessage(device, challengeId, nonce, "session", hash.uppercase()) }
    }

    @Test fun `p256 signature over message verifies with exported spki public key`() {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        val pair = generator.generateKeyPair()
        val message = cameraAuthMessage(device, challengeId, nonce, "register", hash)
        val signature = Signature.getInstance("SHA256withECDSA").run { initSign(pair.private); update(message); sign() }
        val publicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(pair.public.encoded))
        assertTrue(Signature.getInstance("SHA256withECDSA").run { initVerify(publicKey); update(message); verify(signature) })
        val altered = cameraAuthMessage(device, challengeId, nonce, "session", hash)
        assertFalse(Signature.getInstance("SHA256withECDSA").run { initVerify(publicKey); update(altered); verify(signature) })
    }
}
