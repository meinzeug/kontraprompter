package de.kontrakurs.kontraprompter.cloud

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class StudioOwnedProofGateTest {
    private class Store : StudioIdentityStore {
        var marker: StudioIdentityMarker? = null
        var pair: KeyPair? = null
        var id: String? = null
        var writes = 0
        var creates = 0
        var onSign: (() -> Unit)? = null
        var onRead: (() -> Unit)? = null
        override fun marker(): StudioIdentityMarker? { onRead?.invoke(); return marker }
        override fun hasAnyDeviceKey() = pair != null
        override fun key(id: String): StudioIdentityKey? {
            val held=pair ?: return null
            if (this.id != id) return null
            return object : StudioIdentityKey {
                override val publicKeyDer get() = held.public.encoded
                override val nonExportable get() = true // Synthetic software-key test double.
                override fun sign(message: ByteArray): ByteArray {
                    onSign?.invoke()
                    return Signature.getInstance("SHA256withECDSA").run { initSign(held.private); update(message); sign() }
                }
            }
        }
        override fun createKey(id: String): StudioIdentityKey {
            check(pair == null);creates++;this.id=id
            pair=KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
            return checkNotNull(key(id))
        }
        override fun write(marker: StudioIdentityMarker) { writes++;this.marker=marker }
    }
    private val owner="a".repeat(64);private val workspace="b".repeat(64);private val session="c".repeat(64)
    private val challenge="2b8b26bc-d809-4f67-8759-028bb5891002";private val nonce="A".repeat(43)
    private fun approve(gate: StudioOwnedProofGate,id: String)=gate.approveAfterExplicitConsent(id,owner,workspace,session,listOf("projects:read"))
    @Test fun `new process requires fresh approval and proof uses expected identity and binding`() {
        val store=Store();val identity=StudioDeviceIdentity(store);val ready=identity.createAfterExplicitConsent() as StudioIdentityState.Ready
        val writes=store.writes;val first=StudioOwnedProofGate(identity);val approval=approve(first,ready.id)
        val proof=first.enrollment(approval,challenge,nonce)!!
        val text=StudioOwnedDeviceTranscript.enrollment(ready.id,challenge,nonce,ready.publicKeyDer,listOf("projects:read"),owner,workspace,session)
        assertTrue(Signature.getInstance("SHA256withECDSA").run { initVerify(store.pair!!.public);update(text);verify(proof) })
        val restarted=StudioOwnedProofGate(StudioDeviceIdentity(store));assertFalse(restarted.isCurrent(approval));assertNull(restarted.enrollment(approval,challenge,nonce))
        assertEquals(writes,store.writes);assertEquals(1,store.creates);first.close();restarted.close()
    }
    @Test fun `session replacement and lifecycle close reject prior approvals`() {
        val store=Store();val identity=StudioDeviceIdentity(store);val ready=identity.createAfterExplicitConsent() as StudioIdentityState.Ready;val gate=StudioOwnedProofGate(identity)
        val old=approve(gate,ready.id);val newer=gate.approveAfterExplicitConsent(ready.id,owner,workspace,"d".repeat(64),listOf("projects:read"))
        assertNull(gate.enrollment(old,challenge,nonce));assertNotNull(gate.enrollment(newer,challenge,nonce));gate.invalidate()
        assertNull(gate.enrollment(newer,challenge,nonce));val again=approve(gate,ready.id);gate.close();assertNull(gate.enrollment(again,challenge,nonce))
        assertThrows(IllegalStateException::class.java) { approve(gate,ready.id) }
    }
    @Test fun `logout during keystore signing returns no stale proof`() {
        val store=Store();val identity=StudioDeviceIdentity(store);val ready=identity.createAfterExplicitConsent() as StudioIdentityState.Ready;val gate=StudioOwnedProofGate(identity);val approval=approve(gate,ready.id)
        val entered=CountDownLatch(1);val release=CountDownLatch(1);store.onSign={entered.countDown();check(release.await(5,TimeUnit.SECONDS))}
        val pool=Executors.newSingleThreadExecutor();try {
            val result=pool.submit<ByteArray?> { gate.enrollment(approval,challenge,nonce) };assertTrue(entered.await(3,TimeUnit.SECONDS));gate.invalidate();release.countDown()
            assertNull(result.get(5,TimeUnit.SECONDS));assertFalse(gate.isCurrent(approval))
        } finally { release.countDown();pool.shutdownNow();gate.close() }
    }
    @Test fun `invalidation during keystore load cannot approve after logout`() {
        val store=Store();val identity=StudioDeviceIdentity(store);val ready=identity.createAfterExplicitConsent() as StudioIdentityState.Ready;val gate=StudioOwnedProofGate(identity)
        val entered=CountDownLatch(1);val release=CountDownLatch(1);store.onRead={entered.countDown();check(release.await(5,TimeUnit.SECONDS))}
        val pool=Executors.newSingleThreadExecutor();try {
            val result=pool.submit<Boolean> { runCatching { approve(gate,ready.id) }.isFailure }
            assertTrue(entered.await(3,TimeUnit.SECONDS));gate.invalidate();release.countDown();assertTrue(result.get(5,TimeUnit.SECONDS))
        } finally { release.countDown();pool.shutdownNow();gate.close() }
    }
    @Test fun `missing identity cannot self recreate and malformed bindings fail`() {
        val store=Store();val identity=StudioDeviceIdentity(store);val ready=identity.createAfterExplicitConsent() as StudioIdentityState.Ready;val gate=StudioOwnedProofGate(identity)
        assertThrows(IllegalArgumentException::class.java) { gate.approveAfterExplicitConsent(ready.id,owner,workspace,"password",listOf("projects:read")) }
        val old=approve(gate,ready.id);store.pair=null;assertNull(gate.enrollment(old,challenge,nonce));assertEquals(1,store.creates)
        assertThrows(IllegalStateException::class.java) { approve(gate,ready.id) };gate.close()
    }
}
