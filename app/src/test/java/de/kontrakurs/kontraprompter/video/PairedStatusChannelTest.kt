package de.kontrakurs.kontraprompter.video

import de.kontrakurs.kontraprompter.network.AuthenticatedChannel
import de.kontrakurs.kontraprompter.network.LanSession
import de.kontrakurs.kontraprompter.network.LanState
import de.kontrakurs.kontraprompter.test.jvmAllocate
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Real LAN admission and camera-state snapshot; no Android hardware or networking. */
class PairedStatusChannelTest {
    private fun set(target: Any, name: String, value: Any?) {
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
    }
    private fun channel(): AuthenticatedChannel {
        val ctor = AuthenticatedChannel::class.java.declaredConstructors.single { it.parameterCount == 8 }.apply { isAccessible = true }
        return ctor.newInstance(Socket(), DataInputStream(ByteArrayInputStream(byteArrayOf())), DataOutputStream(ByteArrayOutputStream()),
            ByteArray(16), ByteArray(32), ByteArray(32), "host", "fixture") as AuthenticatedChannel
    }
    private fun lan(channel: AuthenticatedChannel): LanSession = jvmAllocate(LanSession::class.java).also {
        set(it, "socketLock", Any()); set(it, "closed", AtomicBoolean(false)); set(it, "connected", AtomicBoolean(true))
        set(it, "_state", MutableStateFlow(LanState(authenticated = true, localApproved = true, remoteApproved = true, ready = true)))
        set(it, "channel", channel)
    }

    @Test fun `snapshot is bound to approved exact channel and recording project`() {
        val active = channel(); val other = channel(); val lan = lan(active)
        val session = jvmAllocate(LiveSession::class.java); val take = UUID.randomUUID().toString(); val project = UUID.randomUUID().toString()
        set(session, "host", true); set(session, "guard", Any()); set(session, "lan", lan)
        set(session, "_recording", MutableStateFlow<LocalRecording>(LocalRecording.Recording(take)))
        set(session, "_cameraSelection", MutableStateFlow(CameraSelection(front = false)))
        set(session, "statusRecordingId", take); set(session, "statusProjectId", project)
        assertNull(session.pairedStatusSnapshot(other))
        val status = session.pairedStatusSnapshot(active)!!
        assertEquals("recording", status.phase); assertEquals(take, status.recordingId)
        assertTrue(status.projectKnown); assertEquals(project, status.projectId); assertEquals(false, status.cameraFront)
        assertTrue(session.pairedStatusSnapshot(active)!!.revision > status.revision)
        set(session, "statusRecordingId", UUID.randomUUID().toString())
        val unknown = session.pairedStatusSnapshot(active)!!
        assertFalse(unknown.projectKnown); assertNull(unknown.projectId)
        (lan.javaClass.getDeclaredField("connected").apply { isAccessible = true }.get(lan) as AtomicBoolean).set(false)
        assertNull(session.pairedStatusSnapshot(active))
    }

    @Test fun `delayed response cannot pass actual admission after disconnect or channel replacement`() {
        val old = channel(); val replacement = channel(); val lan = lan(old); val probe = PairedStatusProbe()
        val observation = PairedStatusObservation(1, "recording", UUID.randomUUID().toString(), false, null, false)
        assertEquals(true, lan.admitOnApprovedConnection(old) { probe.begin(old, "q", 100) })
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val outcome = AtomicReference<Boolean?>()
        val worker = Thread {
            entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
            outcome.set(lan.admitOnApprovedConnection(old) { probe.receive(old, "q", 101, observation) })
        }.apply { isDaemon = true; start() }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val socketLock = lan.javaClass.getDeclaredField("socketLock").apply { isAccessible = true }.get(lan)
        synchronized(socketLock) { set(lan, "channel", replacement) }
        probe.reset(); release.countDown(); worker.join(5_000)
        assertFalse(worker.isAlive); assertNull(outcome.get()); assertNull(probe.result(replacement, 102))
        assertEquals(true, lan.admitOnApprovedConnection(replacement) { probe.begin(replacement, "new", 103) })
        assertEquals(false, lan.admitOnApprovedConnection(replacement) { probe.receive(replacement, "q", 104, observation) })
        assertEquals(true, lan.admitOnApprovedConnection(replacement) { probe.receive(replacement, "new", 104, observation) })
    }
}
