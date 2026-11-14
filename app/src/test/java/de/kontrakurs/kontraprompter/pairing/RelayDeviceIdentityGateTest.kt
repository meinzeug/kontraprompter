package de.kontrakurs.kontraprompter.pairing

import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

/** Software keys exercise gate binding only; Android Keystore needs separate hardware tests. */
class RelayDeviceIdentityGateTest {
    private val localId = "11111111-1111-4111-8111-111111111111"
    private val pairA = "33333333-3333-4333-8333-333333333333"
    private val pairB = "44444444-4444-4444-8444-444444444444"
    private val peerA = PeerBinding(pairA, "22222222-2222-4222-8222-222222222222", "c".repeat(64), RelayRole.CAMERA, 7, PeerTrustState.CONFIRMED)
    private val peerB = PeerBinding(pairB, "55555555-5555-4555-8555-555555555555", "d".repeat(64), RelayRole.CAMERA, 7, PeerTrustState.CONFIRMED)
    private val owner = "a".repeat(64)

    @Test fun signaturesRequireTheExactPeerAuthorizedByGate() {
        val key = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val ctor = RelayDeviceIdentity::class.java.getDeclaredConstructor(String::class.java, ByteArray::class.java, PrivateKey::class.java)
        ctor.isAccessible = true
        val identity = ctor.newInstance(localId, key.public.encoded, key.private)
        val localPin = MessageDigest.getInstance("SHA-256").digest(key.public.encoded).joinToString("") { "%02x".format(it.toInt() and 255) }
        val journal = PeerJournal(owner, localId, localPin, listOf(peerA, peerB))
        val gate = ReconnectGate(peerA) { 1_000L }
        val attempt = gate.begin(true)!!
        val requestId = "66666666-6666-4666-8666-666666666666"
        assertThrows(IllegalStateException::class.java) {
            identity.signReconnect(gate, journal, attempt, owner, localId, pairB, RelayRole.CAMERA, requestId, attempt.challenge, "f".repeat(64), 7)
        }
        assertTrue(identity.signReconnect(gate, journal, attempt, owner, localId, pairA, RelayRole.CAMERA, requestId, attempt.challenge, "f".repeat(64), 7).isNotEmpty())
        assertTrue(gate.serverStatus(attempt, attempt.challenge, pairA, peerA.peerDeviceId, peerA.peerSpkiSha256, 7, false, 10_000))
        assertTrue(gate.journalPersisted(attempt, peerA)); assertTrue(gate.tlsConfirmed(attempt, peerA.peerSpkiSha256))
        val credential = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 7 })
        assertThrows(IllegalStateException::class.java) {
            identity.signDeviceRequest(gate, journal, attempt, pairB, credential, "POST", "/api/turn/credentials", byteArrayOf())
        }
        assertTrue(identity.signDeviceRequest(gate, journal, attempt, pairA, credential, "POST", "/api/turn/credentials", byteArrayOf()).signature.isNotEmpty())
    }
}
