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

class StudioOwnedSharedSessionTest {
    private class KeyStore : StudioIdentityStore {
        var marker: StudioIdentityMarker?=null;var pair: KeyPair?=null;var id: String?=null
        var onSign:(()->Unit)?=null
        override fun marker()=marker
        override fun hasAnyDeviceKey()=pair!=null
        override fun key(id:String):StudioIdentityKey? {
            val held=pair?:return null;if(this.id!=id)return null
            return object:StudioIdentityKey {
                override val publicKeyDer get()=held.public.encoded
                override val nonExportable get()=true // software test double
                override fun sign(message:ByteArray):ByteArray { onSign?.invoke();return Signature.getInstance("SHA256withECDSA").run{initSign(held.private);update(message);sign()} }
            }
        }
        override fun createKey(id:String):StudioIdentityKey {check(pair==null);this.id=id;pair=KeyPairGenerator.getInstance("EC").apply{initialize(ECGenParameterSpec("secp256r1"))}.generateKeyPair();return checkNotNull(key(id))}
        override fun write(marker:StudioIdentityMarker){this.marker=marker}
    }
    private class Store:StudioOwnedConsentStore {
        private var record:StudioOwnedConsentRecord?=null
        var failRead=false;var failCas=false
        override fun read():StudioOwnedConsentRecord?=synchronized(this){if(failRead)error("test read failure");record}
        override fun compareAndSet(expected:StudioOwnedConsentRecord?,update:StudioOwnedConsentRecord):Boolean=synchronized(this){
            if(failCas || record!=expected)false else {record=update;true}
        }
    }
    private val owner="a".repeat(64);private val workspace="b".repeat(64);private val session="c".repeat(64)
    private val challenge="2b8b26bc-d809-4f67-8759-028bb5891002";private val nonce="A".repeat(43)
    private fun newSession(identity:StudioDeviceIdentity,store:Store)=StudioOwnedSharedSession(StudioOwnedProofGate(identity),store)
    private fun approve(s:StudioOwnedSharedSession,id:String)=s.approveAfterExplicitConsent(id,owner,workspace,session,listOf("projects:read"))
    @Test fun `two clients share generation and local logout persists a tombstone`() {
        val ks=KeyStore();val identity=StudioDeviceIdentity(ks);val id=(identity.createAfterExplicitConsent() as StudioIdentityState.Ready).id
        val store=Store();val shared=newSession(identity,store);val first=checkNotNull(approve(shared,id));val clientA=shared;val clientB=shared
        assertNotNull(clientA.enrollment(first,challenge,nonce));assertTrue(clientB.revokeLocal());assertNull(clientA.enrollment(first,challenge,nonce));assertTrue(store.read()!!.revoked)
        val restarted=newSession(StudioDeviceIdentity(ks),store);assertFalse(restarted.isCurrent(first));assertNull(approve(restarted,id))
        shared.close();restarted.close()
    }
    @Test fun `session replacement revokes both clients stale generation`() {
        val ks=KeyStore();val identity=StudioDeviceIdentity(ks);val id=(identity.createAfterExplicitConsent() as StudioIdentityState.Ready).id
        val store=Store();val shared=newSession(identity,store);val old=checkNotNull(approve(shared,id));val next=checkNotNull(approve(shared,id))
        assertNull(shared.enrollment(old,challenge,nonce));assertNotNull(shared.enrollment(next,challenge,nonce));assertEquals(2L,store.read()!!.revision);shared.close()
    }
    @Test fun `a new owner instance observes updated persisted generation`() {
        val ks=KeyStore();val identity=StudioDeviceIdentity(ks);val id=(identity.createAfterExplicitConsent() as StudioIdentityState.Ready).id;val store=Store()
        val old=newSession(identity,store);val first=checkNotNull(approve(old,id));val other=newSession(identity,store);val second=checkNotNull(approve(other,id))
        assertNull(old.enrollment(first,challenge,nonce));assertNotNull(other.enrollment(second,challenge,nonce));old.close();other.close()
    }
    @Test fun `failed durable revoke stays closed and never claims success`() {
        val ks=KeyStore();val identity=StudioDeviceIdentity(ks);val id=(identity.createAfterExplicitConsent() as StudioIdentityState.Ready).id;val store=Store();val shared=newSession(identity,store)
        val lease=checkNotNull(approve(shared,id));store.failCas=true;assertFalse(shared.revokeLocal());assertNull(shared.enrollment(lease,challenge,nonce));assertNull(approve(shared,id))
        // New process is still closed; failure could not persist a tombstone.
        val afterRestart=newSession(StudioDeviceIdentity(ks),store);assertFalse(afterRestart.isCurrent(lease));shared.close();afterRestart.close()
    }
    @Test fun `logout during signer work does not return stale proof`() {
        val ks=KeyStore();val identity=StudioDeviceIdentity(ks);val id=(identity.createAfterExplicitConsent() as StudioIdentityState.Ready).id;val store=Store();val shared=newSession(identity,store);val lease=checkNotNull(approve(shared,id))
        val started=CountDownLatch(1);val release=CountDownLatch(1);ks.onSign={started.countDown();check(release.await(5,TimeUnit.SECONDS))};val pool=Executors.newSingleThreadExecutor()
        try{val pending=pool.submit<ByteArray?>{shared.enrollment(lease,challenge,nonce)};assertTrue(started.await(3,TimeUnit.SECONDS));assertTrue(shared.revokeLocal());release.countDown();assertNull(pending.get(5,TimeUnit.SECONDS));assertTrue(store.read()!!.revoked)}
        finally{release.countDown();pool.shutdownNow();shared.close()}
    }
    @Test fun `unavailable store and wrong owner fail closed without changing record`() {
        val ks=KeyStore();val identity=StudioDeviceIdentity(ks);val id=(identity.createAfterExplicitConsent() as StudioIdentityState.Ready).id;val store=Store();val shared=newSession(identity,store)
        val original=checkNotNull(approve(shared,id));val before=store.read()
        assertNull(shared.approveAfterExplicitConsent(id,"d".repeat(64),workspace,session,listOf("projects:read")));assertEquals(before,store.read());assertNull(shared.enrollment(original,challenge,nonce))
        store.failRead=true;assertNull(approve(shared,id));assertNull(approve(shared,id));shared.close()
    }
}
