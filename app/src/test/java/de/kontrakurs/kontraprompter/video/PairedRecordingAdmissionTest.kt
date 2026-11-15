package de.kontrakurs.kontraprompter.video

import de.kontrakurs.kontraprompter.network.AuthenticatedChannel
import de.kontrakurs.kontraprompter.network.LanSession
import de.kontrakurs.kontraprompter.network.LanState
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import java.util.UUID
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import de.kontrakurs.kontraprompter.test.jvmAllocate

/** Exercises actual LiveSession recording admission without camera, codec or device data. */
class PairedRecordingAdmissionTest {
    private fun set(target: Any, name: String, value: Any?) {
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
    }

    @Test fun `recording start is not admitted after its channel disconnects`() {
        val lan = jvmAllocate(LanSession::class.java)
        val session = jvmAllocate(LiveSession::class.java)
        val video = jvmAllocate(RtcVideo::class.java)
        val socket = Socket()
        val ctor = AuthenticatedChannel::class.java.declaredConstructors.single { it.parameterCount == 8 }.apply { isAccessible = true }
        val oldChannel = ctor.newInstance(socket, DataInputStream(ByteArrayInputStream(byteArrayOf())), DataOutputStream(ByteArrayOutputStream()),
            ByteArray(16), ByteArray(32), ByteArray(32), "host", "test") as AuthenticatedChannel
        val queued = mutableListOf<Runnable>()
        val executor = object : AbstractExecutorService() {
            override fun execute(command: Runnable) { queued.add(command) }
            override fun shutdown() = Unit
            override fun shutdownNow(): MutableList<Runnable> = mutableListOf()
            override fun isShutdown() = false
            override fun isTerminated() = false
            override fun awaitTermination(timeout: Long, unit: TimeUnit) = false
        }
        val recording = MutableStateFlow<LocalRecording>(LocalRecording.Idle)
        val delivery = Any()
        val lanState = MutableStateFlow(LanState(authenticated = true, localApproved = true, remoteApproved = true, ready = true))
        set(lan, "socketLock", Any()); set(lan, "closed", AtomicBoolean(false)); set(lan, "connected", AtomicBoolean(true))
        runCatching { set(lan, "deliveryLock", delivery) }
        set(lan, "_state", lanState); set(lan, "channel", oldChannel)
        set(video, "closed", AtomicBoolean(false)); set(video, "executor", executor); set(video, "host", true)
        set(session, "guard", Any()); set(session, "host", true); set(session, "lan", lan)
        set(session, "_recording", recording); set(session, "_video", MutableStateFlow<RtcVideo?>(video))
        set(session, "_cameraSelection", MutableStateFlow(CameraSelection()))
        val id = UUID.randomUUID().toString()
        val method = LiveSession::class.java.declaredMethods.firstOrNull { it.name.startsWith("startPairedRecording") }
        // Baseline had no channel-bound start entry point: invoke the real old start.
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val finished = CountDownLatch(1)
        val result = AtomicReference<Boolean>()
        // Model the already-entered application callback while the real LAN
        // disconnect invalidates its channel and waits for callback completion.
        Thread {
            synchronized(delivery) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                result.set(if (method == null) session.startRecording(id, null, RecordingOrigin.LAN_UNASSIGNED)
                    else { method.isAccessible = true; method.invoke(session, oldChannel, id, null) as Boolean })
            }
            finished.countDown()
        }.apply { isDaemon = true; start() }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val disconnected = CountDownLatch(1)
        Thread {
            try { LanSession::class.java.getDeclaredMethod("disconnect", String::class.java, AuthenticatedChannel::class.java)
                .apply { isAccessible = true }.invoke(lan, "test disconnect", oldChannel) }
            finally { disconnected.countDown() }
        }.apply { isDaemon = true; start() }
        val limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (lanState.value.ready && System.nanoTime() < limit) Thread.yield()
        assertFalse(lanState.value.ready)
        release.countDown()
        assertTrue(finished.await(5, TimeUnit.SECONDS))
        assertTrue(disconnected.await(5, TimeUnit.SECONDS))
        val admitted = result.get()
        assertFalse("old channel started a recording after disconnect", admitted)
        assertEquals(LocalRecording.Idle, recording.value)
        assertTrue(queued.isEmpty())

        // A still-current approved channel can admit and queue the same sort of action.
        set(lan, "connected", AtomicBoolean(true)); set(lan, "channel", oldChannel)
        lanState.value = LanState(authenticated = true, localApproved = true, remoteApproved = true, ready = true)
        val allowed = if (method == null) session.startRecording(id, null, RecordingOrigin.LAN_UNASSIGNED)
            else method.invoke(session, oldChannel, id, null) as Boolean
        assertTrue(allowed)
        assertEquals(LocalRecording.Preparing(id), recording.value)
        assertEquals(1, queued.size)
    }
}
