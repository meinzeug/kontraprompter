package de.kontrakurs.kontraprompter.cloud

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class StudioOwnedAutoRestoreTest {
    private class KeyStore:StudioIdentityStore {
        var marker:StudioIdentityMarker?=null;var pair:KeyPair?=null;var id:String?=null;var writes=0;var creates=0
        override fun marker()=marker
        override fun hasAnyDeviceKey()=pair!=null
        override fun key(id:String):StudioIdentityKey? {
            val held=pair?:return null;if(id!=this.id)return null
            return object:StudioIdentityKey {
                override val publicKeyDer get()=held.public.encoded
                override val nonExportable get()=true
                override fun sign(message:ByteArray)=Signature.getInstance("SHA256withECDSA").run{initSign(held.private);update(message);sign()}
            }
        }
        override fun createKey(id:String):StudioIdentityKey {check(pair==null);creates++;this.id=id;pair=KeyPairGenerator.getInstance("EC").apply{initialize(ECGenParameterSpec("secp256r1"))}.generateKeyPair();return checkNotNull(key(id))}
        override fun write(marker:StudioIdentityMarker){writes++;this.marker=marker}
    }
    private class Transport(private val ks:KeyStore,private val owner:String,private val workspace:String):DeviceLoginTransport {
        val challenge=UUID.randomUUID().toString();val nonce="A".repeat(43);var begins=0;var completes=0;var revoked=false;var beforeReply:(()->Unit)?=null
        override fun begin(deviceId:String):DeviceChallenge {begins++;return DeviceChallenge(challenge,"$challenge.1000.$nonce.$nonce",60_000)}
        override fun complete(deviceId:String,challengeToken:String,signature:ByteArray):DeviceTicket {
            completes++;val message=StudioOwnedDeviceTranscript.login(deviceId,challenge,challengeToken,ks.pair!!.public.encoded,listOf("projects:read","recordings:upload"),owner,workspace)
            check(Signature.getInstance("SHA256withECDSA").run{initVerify(ks.pair!!.public);update(message);verify(signature)})
            beforeReply?.invoke();check(!revoked);return DeviceTicket("A".repeat(43),300_000)
        }
    }
    private fun hash(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it.toInt() and 0xff)}
    private data class Fixture(val ks:KeyStore,val identity:StudioDeviceIdentity,val ready:StudioIdentityState.Ready,val transport:Transport,var record:StudioOwnedRegistration)
    private fun fixture():Fixture {
        val ks=KeyStore();val identity=StudioDeviceIdentity(ks);val ready=identity.createAfterExplicitConsent() as StudioIdentityState.Ready
        val owner="a".repeat(64);val workspace="b".repeat(64);val transport=Transport(ks,owner,workspace)
        return Fixture(ks,identity,ready,transport,StudioOwnedRegistration("https://videomaker.dorfspy.de",ready.id,hash(ready.publicKeyDer),owner,workspace,1,true))
    }
    private fun restore(f:Fixture)=StudioOwnedAutoRestore(f.identity,StudioOwnedRegistrationStore{f.record},DeviceLoginCoordinator{1000},f.transport)
    @Test fun `valid enrolled binding gets fresh scoped ticket without storing password`() {
        val f=fixture();val before=f.ks.writes;val restore=restore(f);assertNotNull(restore.restore());assertEquals(1,f.transport.begins);assertEquals(1,f.transport.completes)
        assertEquals(before,f.ks.writes);assertEquals(1,f.ks.creates);restore.stop()
    }
    @Test fun `wrong host owner identity missing key and revoked registration fail closed`() {
        val f=fixture();val old=f.record
        for(value in listOf(old.copy(origin="https://other.invalid"),old.copy(publicKeyHash="0".repeat(64)),old.copy(active=false),old.copy(ownerHash="X".repeat(64)),old.copy(revision=0))){f.record=value;assertNull(restore(f).restore())}
        f.record=old;f.ks.pair=null;assertNull(restore(f).restore());assertEquals(0,f.transport.begins);assertEquals(1,f.ks.creates)
    }
    @Test fun `changed registration while server reply is delayed never yields a ticket`() {
        val f=fixture();val start=CountDownLatch(1);val release=CountDownLatch(1);f.transport.beforeReply={start.countDown();check(release.await(5,TimeUnit.SECONDS))}
        val restore=restore(f);val pool=Executors.newSingleThreadExecutor();try{
            val pending=pool.submit<DeviceLoginCoordinator.Session?>{restore.restore()};assertTrue(start.await(3,TimeUnit.SECONDS));f.record=f.record.copy(revision=2,active=false);release.countDown();assertNull(pending.get(5,TimeUnit.SECONDS))
        }finally{release.countDown();pool.shutdownNow();restore.stop()}
    }
    @Test fun `logout while server reply is delayed cannot restore old session`() {
        val f=fixture();val start=CountDownLatch(1);val release=CountDownLatch(1);f.transport.beforeReply={start.countDown();check(release.await(5,TimeUnit.SECONDS))}
        val restore=restore(f);val pool=Executors.newSingleThreadExecutor();try{
            val pending=pool.submit<DeviceLoginCoordinator.Session?>{restore.restore()};assertTrue(start.await(3,TimeUnit.SECONDS));restore.stop();release.countDown();assertNull(pending.get(5,TimeUnit.SECONDS));assertNull(restore.restore())
        }finally{release.countDown();pool.shutdownNow()}
    }
    @Test fun `server refusal stays offline`() {val f=fixture();f.transport.revoked=true;val restore=restore(f);assertNull(restore.restore());restore.stop()}
}
