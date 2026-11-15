package de.kontrakurs.kontraprompter.video

import de.kontrakurs.kontraprompter.network.AuthenticatedChannel
import de.kontrakurs.kontraprompter.network.DiscoveryServices
import de.kontrakurs.kontraprompter.network.FoundDevice
import de.kontrakurs.kontraprompter.network.LanSession
import de.kontrakurs.kontraprompter.network.LanState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
import de.kontrakurs.kontraprompter.test.jvmAllocate

/** Actual LiveSession admission with synthetic recorder and queue; no camera or codec. */
class PairedRecordingControlTest {
    private fun set(target: Any, name: String, value: Any?) {
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
    }
    private class Fixture {
        val lan = jvmAllocate(LanSession::class.java)
        val session = jvmAllocate(LiveSession::class.java)
        val video = jvmAllocate(RtcVideo::class.java)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val state = MutableStateFlow<LocalRecording>(LocalRecording.Idle)
        val camera = MutableStateFlow(CameraSelection(front = false, canSwitch = true))
        val queued = mutableListOf<Runnable>()
        val marker: AuthenticatedChannel
        val control: Control
        init {
            val ctor = AuthenticatedChannel::class.java.declaredConstructors.single { it.parameterCount == 8 }.apply { isAccessible = true }
            marker = ctor.newInstance(Socket(), DataInputStream(ByteArrayInputStream(byteArrayOf())), DataOutputStream(ByteArrayOutputStream()),
                ByteArray(16), ByteArray(32), ByteArray(32), "host", "test") as AuthenticatedChannel
            fun field(target: Any, name: String, value: Any?) { target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value) }
            field(lan, "socketLock", Any()); field(lan, "closed", AtomicBoolean(false)); field(lan, "connected", AtomicBoolean(true))
            field(lan, "deliveryLock", Any()); field(lan, "scope", scope)
            field(lan, "discoveryEventLock", Any()); field(lan, "discovered", DiscoveryServices<FoundDevice>())
            field(lan, "_state", MutableStateFlow(LanState(authenticated = true, localApproved = true, remoteApproved = true, ready = true)))
            field(lan, "channel", marker)
            val queue = object : AbstractExecutorService() {
                override fun execute(command: Runnable) {
                    val done = CountDownLatch(1)
                    Thread { lan.isApprovedConnection(marker); done.countDown() }.apply { isDaemon = true; start() }
                    check(done.await(2, TimeUnit.SECONDS)) { "LAN lock held during camera submission" }
                    queued.add(command)
                }
                override fun shutdown() = Unit
                override fun shutdownNow(): MutableList<Runnable> = mutableListOf()
                override fun isShutdown() = false
                override fun isTerminated() = false
                override fun awaitTermination(timeout: Long, unit: TimeUnit) = false
            }
            field(video, "closed", AtomicBoolean(false)); field(video, "host", true); field(video, "executor", queue)
            field(session, "guard", Any()); field(session, "host", true); field(session, "lan", lan); field(session, "scope", scope)
            field(session, "_recording", state); field(session, "_cameraSelection", camera); field(session, "_video", MutableStateFlow<RtcVideo?>(video))
            control = Control(lan, marker)
            field(session, "recorder", control)
        }
        fun disconnect() { setField(lan, "channel", null); setField(lan, "connected", AtomicBoolean(false)) }
        fun setClosed() { setField(session, "closed", true) }
        fun setWebExclusive() { setField(session, "remoteExclusive", true) }
        fun close() = scope.cancel()
        private fun setField(target: Any, name: String, value: Any?) { target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value) }
    }
    private class Control(private val lan: LanSession, private val marker: Any) : RecordingControl {
        var pauses = 0; var resumes = 0; var stops = 0
        var onPause: (() -> Unit)? = null
        private fun assertLanLockAvailable() {
            val done = CountDownLatch(1)
            Thread { lan.isApprovedConnection(marker); done.countDown() }.apply { isDaemon = true; start() }
            check(done.await(2, TimeUnit.SECONDS)) { "LAN lock held during recorder operation" }
        }
        override fun pause(): Boolean { assertLanLockAvailable(); onPause?.invoke(); pauses++; return true }
        override fun resume(): Boolean { assertLanLockAvailable(); resumes++; return true }
        override fun stop(): Boolean { assertLanLockAvailable(); stops++; return true }
        override fun abort(message: String) = Unit
    }

    @Test fun `disconnect invalidates channel even while an already admitted recorder call blocks`() {
        val f = Fixture(); val id = UUID.randomUUID().toString()
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val finished = CountDownLatch(1)
        val disconnected = CountDownLatch(1)
        try {
            f.state.value = LocalRecording.Recording(id)
            val sessionGuard = checkNotNull(LiveSession::class.java.getDeclaredField("guard").apply { isAccessible = true }.get(f.session))
            f.lan.onDisconnect = { synchronized(sessionGuard) {} }
            f.control.onPause = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            Thread { try { f.session.pausePairedRecording(f.marker, id) } finally { finished.countDown() } }
                .apply { isDaemon = true; start() }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            Thread {
                try { LanSession::class.java.getDeclaredMethod("disconnect", String::class.java, AuthenticatedChannel::class.java)
                    .apply { isAccessible = true }.invoke(f.lan, "test disconnect", f.marker) }
                finally { disconnected.countDown() }
            }.apply { isDaemon = true; start() }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (f.lan.isApprovedConnection(f.marker) && System.nanoTime() < deadline) Thread.yield()
            assertFalse(f.lan.isApprovedConnection(f.marker))
            assertFalse("disconnect callback unexpectedly passed session guard", disconnected.await(100, TimeUnit.MILLISECONDS))
            release.countDown()
            assertTrue(finished.await(5, TimeUnit.SECONDS))
            assertTrue(disconnected.await(5, TimeUnit.SECONDS))
            // The operation was admitted before disconnect and may finish.
            assertEquals(1, f.control.pauses)
            assertEquals(LocalRecording.Paused(id), f.state.value)
        } finally { release.countDown(); f.close() }
    }

    @Test fun `paused old callback cannot admit a pause after real disconnect`() {
        val f = Fixture(); val id = UUID.randomUUID().toString()
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val finished = CountDownLatch(1)
        val disconnected = CountDownLatch(1); val admitted = AtomicBoolean(true)
        val delivery = checkNotNull(LanSession::class.java.getDeclaredField("deliveryLock").apply { isAccessible = true }.get(f.lan))
        try {
            f.state.value = LocalRecording.Recording(id)
            Thread {
                try {
                    synchronized(delivery) {
                        entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
                        admitted.set(f.session.pausePairedRecording(f.marker, id))
                    }
                } finally { finished.countDown() }
            }.apply { isDaemon = true; start() }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            Thread {
                try { LanSession::class.java.getDeclaredMethod("disconnect", String::class.java, AuthenticatedChannel::class.java)
                    .apply { isAccessible = true }.invoke(f.lan, "test disconnect", f.marker) }
                finally { disconnected.countDown() }
            }.apply { isDaemon = true; start() }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (f.lan.isApprovedConnection(f.marker) && System.nanoTime() < deadline) Thread.yield()
            assertFalse(f.lan.isApprovedConnection(f.marker))
            release.countDown()
            assertTrue(finished.await(5, TimeUnit.SECONDS)); assertTrue(disconnected.await(5, TimeUnit.SECONDS))
            assertFalse(admitted.get()); assertEquals(0, f.control.pauses)
            assertEquals(LocalRecording.Recording(id), f.state.value)
        } finally { release.countDown(); f.close() }
    }

    @Test fun `stale channel cannot pause resume stop or queue camera switch`() {
        val f = Fixture(); val id = UUID.randomUUID().toString()
        try {
            f.disconnect()
            f.state.value = LocalRecording.Recording(id)
            assertFalse(f.session.pausePairedRecording(f.marker, id))
            assertEquals(LocalRecording.Recording(id), f.state.value)
            f.state.value = LocalRecording.Paused(id)
            assertFalse(f.session.resumePairedRecording(f.marker, id))
            assertEquals(LocalRecording.Paused(id), f.state.value)
            assertFalse(f.session.stopPairedRecording(f.marker, id))
            f.state.value = LocalRecording.Idle
            assertFalse(f.session.requestPairedCameraSwitch(f.marker, false))
            assertFalse(f.camera.value.switching)
            assertTrue(f.queued.isEmpty())
            assertEquals(0, f.control.pauses + f.control.resumes + f.control.stops)
        } finally { f.close() }
    }

    @Test fun `valid channel and recording generation admit controls without LAN lock during recorder work`() {
        val f = Fixture(); val id = UUID.randomUUID().toString(); val wrong = UUID.randomUUID().toString()
        try {
            f.state.value = LocalRecording.Recording(id)
            assertFalse(f.session.pausePairedRecording(f.marker, wrong))
            assertTrue(f.session.pausePairedRecording(f.marker, id))
            assertEquals(LocalRecording.Paused(id), f.state.value)
            assertFalse(f.session.resumePairedRecording(f.marker, wrong))
            assertTrue(f.session.resumePairedRecording(f.marker, id))
            assertEquals(LocalRecording.Recording(id), f.state.value)
            assertFalse(f.session.stopPairedRecording(f.marker, wrong))
            assertTrue(f.session.stopPairedRecording(f.marker, id))
            assertEquals(1, f.control.pauses); assertEquals(1, f.control.resumes); assertEquals(1, f.control.stops)
        } finally { f.close() }
    }

    @Test fun `camera switch is admitted only for expected camera and active channel`() {
        val f = Fixture()
        try {
            assertFalse(f.session.requestPairedCameraSwitch(f.marker, true))
            assertFalse(f.camera.value.switching)
            assertTrue(f.session.requestPairedCameraSwitch(f.marker, false))
            assertTrue(f.camera.value.switching)
            assertEquals(1, f.queued.size)
        } finally { f.close() }
    }

    @Test fun `closed or replacement session rejects prior marker while local control remains available`() {
        val old = Fixture(); val next = Fixture(); val id = UUID.randomUUID().toString()
        try {
            old.state.value = LocalRecording.Recording(id); old.setClosed()
            assertFalse(old.session.pausePairedRecording(old.marker, id))
            next.state.value = LocalRecording.Recording(id)
            assertFalse(next.session.pausePairedRecording(old.marker, id))
            next.disconnect()
            assertTrue(next.session.pauseRecording(id))
            assertEquals(1, next.control.pauses)
        } finally { old.close(); next.close() }
    }

    @Test fun `actual session close rejects all subsequent paired control`() {
        val f = Fixture(); val id = UUID.randomUUID().toString()
        try {
            f.state.value = LocalRecording.Recording(id)
            f.session.close()
            // Closing the session deliberately stops its active recording once.
            assertEquals(1, f.control.stops)
            assertFalse(f.session.pausePairedRecording(f.marker, id))
            assertFalse(f.session.resumePairedRecording(f.marker, id))
            assertFalse(f.session.stopPairedRecording(f.marker, id))
            assertFalse(f.session.requestPairedCameraSwitch(f.marker, false))
            assertEquals(0, f.control.pauses + f.control.resumes)
            assertEquals(1, f.control.stops)
        } finally { f.close() }
    }

    @Test fun `paired stop cannot stop a web exclusive recording but local recovery can`() {
        val id = UUID.randomUUID().toString()
        for (phase in listOf(LocalRecording.Preparing(id), LocalRecording.Recording(id), LocalRecording.Paused(id))) {
            val f = Fixture()
            try {
                f.setWebExclusive(); f.state.value = phase
                assertFalse(f.session.stopPairedRecording(f.marker, id))
                assertEquals(phase, f.state.value)
                assertEquals(0, f.control.stops)
                val stopFlag = LiveSession::class.java.getDeclaredField("stopWhenStarted").apply { isAccessible = true }.getBoolean(f.session)
                assertFalse(stopFlag)
                assertTrue(f.session.stopRecording(id))
                assertEquals(1, f.control.stops)
            } finally { f.close() }
        }
    }
}
