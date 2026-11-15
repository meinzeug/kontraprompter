package de.kontrakurs.kontraprompter.camera

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class WebCameraStatusProbeTest {
    private fun binding() = WebCameraStatusProbe.Binding("a".repeat(64), "b".repeat(64), "c".repeat(64), UUID.randomUUID().toString(), UUID.randomUUID().toString(), UUID.randomUUID().toString(), Any())
    private fun status() = WebCameraStatusProbe.CameraStatus("recording", UUID.randomUUID().toString(), false)

    @Test fun `only same sessions epoch channel and query can finish once`() {
        val gate=WebCameraStatusProbe();val owner=binding();val id=UUID.randomUUID().toString();val value=status()
        assertTrue(gate.begin(owner,id,100))
        assertNull(gate.accept(owner.copy(connection=Any()),id,101,value))
        assertNull(gate.accept(owner.copy(epoch=UUID.randomUUID().toString()),id,101,value))
        assertNull(gate.accept(owner.copy(cameraId=UUID.randomUUID().toString()),id,101,value))
        assertNull(gate.accept(owner.copy(prompterId=UUID.randomUUID().toString()),id,101,value))
        assertNull(gate.accept(owner.copy(browserSession="d".repeat(64)),id,101,value))
        assertNull(gate.accept(owner.copy(deviceSession="d".repeat(64)),id,101,value))
        assertNull(gate.accept(owner,UUID.randomUUID().toString(),101,value))
        assertEquals(value,gate.accept(owner,id,101,value));assertNull(gate.accept(owner,id,102,value));assertFalse(gate.begin(owner,id,103))
    }
    @Test fun `expiry and clock rollback never produce a status`() {
        val gate=WebCameraStatusProbe();val owner=binding();val id=UUID.randomUUID().toString()
        assertTrue(gate.begin(owner,id,100));assertNull(gate.accept(owner,id,10_000_000_100L,status()))
        assertNull(gate.accept(owner,id,101,status()))
        val next=UUID.randomUUID().toString();assertTrue(gate.begin(owner,next,100));assertNull(gate.accept(owner,next,99,status()))
        assertNull(gate.accept(owner,next,101,status()))
    }
    @Test fun `lost reply expires and allows a fresh request without replay`() {
        val gate=WebCameraStatusProbe();val owner=binding();val old=UUID.randomUUID().toString();val next=UUID.randomUUID().toString()
        assertTrue(gate.begin(owner,old,100));assertFalse(gate.begin(owner,next,101))
        assertTrue(gate.begin(owner,next,10_000_000_100L));assertNull(gate.accept(owner,old,10_000_000_101L,status()))
        assertNotNull(gate.accept(owner,next,10_000_000_101L,status()))
    }
    @Test fun `wrong invalidation cannot cancel but matching logout can`() {
        val gate=WebCameraStatusProbe();val owner=binding();val id=UUID.randomUUID().toString()
        assertTrue(gate.begin(owner,id,1));gate.invalidate(owner.copy(connection=Any()));assertFalse(gate.begin(owner,id,2))
        gate.invalidate(owner);assertNull(gate.accept(owner,id,3,status()));assertFalse(gate.begin(owner,id,4));assertTrue(gate.begin(owner,UUID.randomUUID().toString(),4))
    }
    @Test fun `malformed camera status is not accepted`() {
        val gate=WebCameraStatusProbe();val owner=binding();val id=UUID.randomUUID().toString()
        assertTrue(gate.begin(owner,id,1));assertNull(gate.accept(owner,id,2,WebCameraStatusProbe.CameraStatus("recording",null,true)))
        assertNull(gate.accept(owner,id,2,WebCameraStatusProbe.CameraStatus("made-up",null,true)))
        assertNotNull(gate.accept(owner,id,3,WebCameraStatusProbe.CameraStatus("idle",null,true)))
    }
    @Test fun `simultaneous replies admit exactly one`() {
        val gate=WebCameraStatusProbe();val owner=binding();val id=UUID.randomUUID().toString();val ready=CountDownLatch(2);val start=CountDownLatch(1);val won=AtomicInteger()
        assertTrue(gate.begin(owner,id,1))
        val workers=List(2) { Thread { ready.countDown();check(start.await(5,TimeUnit.SECONDS));if(gate.accept(owner,id,2,status())!=null)won.incrementAndGet() } }
        workers.forEach(Thread::start)
        try { assertTrue(ready.await(5,TimeUnit.SECONDS));start.countDown();workers.forEach { it.join(5000) };assertEquals(1,won.get()) }
        finally { start.countDown();workers.forEach { it.join(5000) } }
    }
}
