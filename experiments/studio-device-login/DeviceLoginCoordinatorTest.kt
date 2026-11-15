package de.kontrakurs.kontraprompter.cloud

import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.*
import org.junit.Test

class DeviceLoginCoordinatorTest {
    private val id=UUID.randomUUID().toString()
    private val challengeId=UUID.randomUUID().toString()
    private val text="A".repeat(43)
    private val challenge=DeviceChallenge(challengeId,"$challengeId.1000.$text.$text",60_000)
    private val signer=DeviceLoginSigner { byteArrayOf(1,2,3) }
    private fun transport(token: String=text) = object : DeviceLoginTransport {
        override fun begin(deviceId: String) = challenge
        override fun complete(deviceId: String, challengeToken: String, signature: ByteArray) = DeviceTicket(token,300_000)
    }

    @Test fun `old unauthorized response cannot invalidate newer login`() {
        val time=AtomicLong(1000);val client=DeviceLoginCoordinator(time::get)
        val first=checkNotNull(client.login(id,transport(),signer));val second=checkNotNull(client.login(id,transport("E".repeat(42)+"A"),signer))
        assertFalse(client.unauthorized(first));assertSame(second,client.snapshot())
        assertTrue(client.unauthorized(second));assertNull(client.snapshot())
    }

    @Test fun `logout while complete is blocked discards late result and stops automatic login`() {
        val time=AtomicLong(1000);val client=DeviceLoginCoordinator(time::get);val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val waiting=object : DeviceLoginTransport {
            override fun begin(deviceId: String)=challenge
            override fun complete(deviceId: String,challengeToken: String,signature: ByteArray): DeviceTicket { entered.countDown();check(release.await(5,TimeUnit.SECONDS));return DeviceTicket(text,300_000) }
        }
        val pool=Executors.newSingleThreadExecutor()
        try {
            val future=pool.submit<DeviceLoginCoordinator.Session?> { client.login(id,waiting,signer) }
            assertTrue(entered.await(5,TimeUnit.SECONDS));client.logout();release.countDown()
            assertNull(future.get(5,TimeUnit.SECONDS));assertNull(client.snapshot());assertNull(client.login(id,transport(),signer))
            client.explicitEnrollmentConfirmed();assertNotNull(client.login(id,transport(),signer))
        } finally { release.countDown();pool.shutdownNow() }
    }

    @Test fun `superseded slow login cannot overwrite newer session`() {
        val time=AtomicLong(1000);val client=DeviceLoginCoordinator(time::get);val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val slow=object : DeviceLoginTransport {
            override fun begin(deviceId: String): DeviceChallenge { entered.countDown();check(release.await(5,TimeUnit.SECONDS));return challenge }
            override fun complete(deviceId: String,challengeToken: String,signature: ByteArray)=DeviceTicket(text,300_000)
        }
        val pool=Executors.newSingleThreadExecutor()
        try {
            val future=pool.submit<DeviceLoginCoordinator.Session?> { client.login(id,slow,signer) }
            assertTrue(entered.await(5,TimeUnit.SECONDS));val newer=checkNotNull(client.login(id,transport(),signer));release.countDown()
            assertNull(future.get(5,TimeUnit.SECONDS));assertSame(newer,client.snapshot())
        } finally { release.countDown();pool.shutdownNow() }
    }

    @Test fun `invalid challenge or expired response never becomes a session`() {
        val time=AtomicLong(1000);val client=DeviceLoginCoordinator(time::get);var signed=false
        val bad=object : DeviceLoginTransport {
            override fun begin(deviceId: String)=challenge.copy(challengeId=UUID.randomUUID().toString())
            override fun complete(deviceId: String,challengeToken: String,signature: ByteArray):DeviceTicket = error("must not complete")
        }
        assertNull(client.login(id,bad,DeviceLoginSigner { signed=true;byteArrayOf(1) }));assertFalse(signed)
        val slow=object : DeviceLoginTransport {
            override fun begin(deviceId: String):DeviceChallenge { time.addAndGet(60_001);return challenge }
            override fun complete(deviceId: String,challengeToken: String,signature: ByteArray):DeviceTicket = error("must not complete")
        }
        assertNull(client.login(id,slow,signer));assertNull(client.snapshot())
    }

    @Test fun `clock rollback invalidates existing session and blocks silent renewal`() {
        val time=AtomicLong(1000);val client=DeviceLoginCoordinator(time::get)
        assertNotNull(client.login(id,transport(),signer));time.set(999)
        assertNull(client.snapshot());time.set(1001);assertNull(client.login(id,transport(),signer))
    }
}
