package de.kontrakurs.kontraprompter.video

import org.junit.Assert.*
import org.junit.Test
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

class WebStatusProofTranscriptTest {
    private val binding = WebStatusProofBinding(
        "a".repeat(64), "b".repeat(64), "c".repeat(64),
        "00000000-0000-0000-0000-000000000001", "00000000-0000-0000-0000-000000000002",
        "00000000-0000-0000-0000-000000000003", "00000000-0000-0000-0000-000000000004",
        "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE",
    )
    private val state = PairedStatusObservation(42, "recording", "00000000-0000-0000-0000-000000000005", true, "00000000-0000-0000-0000-000000000006", false)

    @Test fun `server fixture transcript hash and P256 DER signature match JVM`() {
        val bytes = WebStatusProofTranscript.encode(binding, state)!!
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        assertEquals("bb3740e5796162f5c1a546a3285cb8f5cd4f6fac4deb816894bf352a34e79d14", hash)
        val key = Base64.getDecoder().decode("MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEHAYt0AYAlUXbu087VXPuHHRDEeso8RxG+V5Wh7zKaOQrt9vWx2eXwkEIsnVbVR8E7CKbk58EgUhxJweLXW2Qyg==")
        val signature = Base64.getDecoder().decode("MEUCIGGngfEazx6T85O/vaNt40YLw8pzzFtzLyEkmYP2eFOtAiEAnDlyBoxHCWmQLmFL2Bhh5/g1hjeS3K8DBFuxD4tZdNg=")
        val verifier = Signature.getInstance("SHA256withECDSA")
        verifier.initVerify(KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(key))); verifier.update(bytes)
        assertTrue(verifier.verify(signature))
        val changed = WebStatusProofTranscript.encode(binding, state.copy(revision = 43))!!
        verifier.initVerify(KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(key))); verifier.update(changed)
        assertFalse(verifier.verify(signature))
    }
    @Test fun `invalid identities and ambiguous observation are rejected`() {
        assertNull(WebStatusProofTranscript.encode(binding.copy(cameraId = binding.prompterId), state))
        assertNull(WebStatusProofTranscript.encode(binding.copy(nonce = "x".repeat(43)), state))
        assertNull(WebStatusProofTranscript.encode(binding, state.copy(recordingId = null)))
        assertNull(WebStatusProofTranscript.encode(binding, state.copy(projectKnown = false)))
        assertNull(WebStatusProofTranscript.encode(binding, state.copy(revision = 9_007_199_254_740_992L)))
    }
}
