package de.kontrakurs.kontraprompter.cloud

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import org.junit.Assert.*
import org.junit.Test

class StudioDeviceIdentityTest {
    private class FakeStore : StudioIdentityStore {
        var value: StudioIdentityMarker? = null
        var pair: KeyPair? = null
        var keyId: String? = null
        var creates = 0
        var writes = 0
        var failReady = false
        var exportable = false
        var mismatchedSigner = false
        override fun marker() = value
        override fun hasAnyDeviceKey() = pair != null
        override fun key(id: String): StudioIdentityKey? {
            val found = pair ?: return null
            if (id != keyId) return null
            return object : StudioIdentityKey {
                override val publicKeyDer get() = found.public.encoded.copyOf()
                override val nonExportable get() = !exportable
                override fun sign(message: ByteArray): ByteArray {
                    val signerKey = if (mismatchedSigner) newPair().private else found.private
                    return Signature.getInstance("SHA256withECDSA").run { initSign(signerKey); update(message); sign() }
                }
            }
        }
        override fun createKey(id: String): StudioIdentityKey {
            check(pair == null); creates++; keyId = id; pair = newPair(); return checkNotNull(key(id))
        }
        override fun write(marker: StudioIdentityMarker) {
            if (marker.state == "ready" && failReady) error("simulated disk failure")
            writes++; value = marker
        }
        companion object {
            fun newPair() = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        }
    }

    @Test fun `load does not create and explicit enrollment is stable`() {
        val store = FakeStore(); val identity = StudioDeviceIdentity(store)
        assertEquals(StudioIdentityState.EnrollmentRequired,identity.load()); assertEquals(0,store.creates)
        val ready = identity.createAfterExplicitConsent() as StudioIdentityState.Ready
        val afterRestart = StudioDeviceIdentity(store).load() as StudioIdentityState.Ready
        assertEquals(ready.id,afterRestart.id);assertArrayEquals(ready.publicKeyDer,afterRestart.publicKeyDer)
        assertEquals(1,store.creates);assertEquals(StudioIdentityState.RecoveryRequired,identity.createAfterExplicitConsent())
        assertEquals(1,store.creates)
    }

    @Test fun `missing or replaced key and orphan marker never self heal`() {
        val store = FakeStore();val identity = StudioDeviceIdentity(store)
        val ready = identity.createAfterExplicitConsent() as StudioIdentityState.Ready
        val old = store.pair;store.pair=null
        assertEquals(StudioIdentityState.RecoveryRequired,identity.load())
        assertEquals(StudioIdentityState.RecoveryRequired,identity.createAfterExplicitConsent());assertEquals(1,store.creates)
        store.pair=FakeStore.newPair();assertEquals(StudioIdentityState.RecoveryRequired,identity.load())
        store.pair=old;store.value=null
        assertEquals(StudioIdentityState.RecoveryRequired,identity.load())
        assertEquals(StudioIdentityState.RecoveryRequired,identity.createAfterExplicitConsent());assertEquals(1,store.creates)
        assertTrue(ready.id.isNotBlank())
    }

    @Test fun `interrupted enrollment requires recovery and never retries generation`() {
        val store=FakeStore();store.failReady=true;val identity=StudioDeviceIdentity(store)
        assertEquals(StudioIdentityState.RecoveryRequired,identity.createAfterExplicitConsent())
        assertEquals("creating",store.value?.state);assertEquals(1,store.creates)
        store.failReady=false
        assertEquals(StudioIdentityState.RecoveryRequired,StudioDeviceIdentity(store).load())
        assertEquals(StudioIdentityState.RecoveryRequired,identity.createAfterExplicitConsent());assertEquals(1,store.creates)
    }

    @Test fun `fixture signature must correspond to loaded nonexportable identity`() {
        val store=FakeStore();val identity=StudioDeviceIdentity(store)
        val ready=identity.createAfterExplicitConsent() as StudioIdentityState.Ready
        val challenge="2b8b26bc-d809-4f67-8759-028bb5891002";val nonce="A".repeat(43);val scope=listOf("projects:read")
        fun sign() = identity.signEnrollmentForFixture(ready.id,challenge,nonce,scope,"a".repeat(64))
        assertTrue(sign().isNotEmpty())
        store.mismatchedSigner=true
        assertThrows(IllegalStateException::class.java) { sign() }
        store.mismatchedSigner=false;store.exportable=true
        assertEquals(StudioIdentityState.RecoveryRequired,identity.load())
        assertThrows(IllegalStateException::class.java) { sign() }
    }

    @Test fun `two clients cannot concurrently create two identities`() {
        val store=FakeStore();val pool=Executors.newFixedThreadPool(2);val start=CountDownLatch(1)
        try {
            val results=(1..2).map { pool.submit<StudioIdentityState> { start.await();StudioDeviceIdentity(store).createAfterExplicitConsent() } }
            start.countDown();val states=results.map { it.get() }
            assertEquals(1,states.count { it is StudioIdentityState.Ready });assertEquals(1,store.creates)
        } finally { pool.shutdownNow() }
    }
}
