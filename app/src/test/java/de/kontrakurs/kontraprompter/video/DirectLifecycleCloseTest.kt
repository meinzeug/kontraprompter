package de.kontrakurs.kontraprompter.video

import de.kontrakurs.kontraprompter.network.DiscoveryServices
import de.kontrakurs.kontraprompter.network.FoundDevice
import de.kontrakurs.kontraprompter.network.LanSession
import de.kontrakurs.kontraprompter.test.jvmAllocate
import java.net.InetAddress
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

/** Real LiveSession.close with synthetic recorder/executor; no native camera or sockets. */
class DirectLifecycleCloseTest {
    private fun set(target: Any, name: String, value: Any?) {
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
    }

    @Test fun `lifecycle close requests peer stop even if recording never finalizes`() {
        val queued = mutableListOf<Runnable>()
        val executor = object : AbstractExecutorService() {
            override fun execute(command: Runnable) { queued.add(command) }
            override fun shutdown() = Unit
            override fun shutdownNow(): MutableList<Runnable> = mutableListOf()
            override fun isShutdown() = false
            override fun isTerminated() = false
            override fun awaitTermination(timeout: Long, unit: TimeUnit) = false
        }
        val video = jvmAllocate(RtcVideo::class.java)
        val generation = PrimaryPeerGeneration()
        val originalGeneration = generation.current()
        val stop = DirectPeerStop({ action -> executor.execute { action() }; true }, { true })
        set(video, "executor", executor); set(video, "closed", AtomicBoolean(false))
        set(video, "primaryGeneration", generation); set(video, "directPeerStop", stop)
        val addresses = LanSession.SocketAddresses(Any(), InetAddress.getByName("192.0.2.1"), InetAddress.getByName("192.0.2.2"))
        val directCtor = RtcVideo::class.java.declaredClasses.single { it.simpleName == "DirectPath" }.declaredConstructors.single().apply { isAccessible = true }
        set(video, "directPath", directCtor.newInstance(addresses, "p2p0"))

        val lan = jvmAllocate(LanSession::class.java)
        set(lan, "closed", AtomicBoolean(false)); set(lan, "socketLock", Any()); set(lan, "discoveryEventLock", Any())
        set(lan, "discovered", DiscoveryServices<FoundDevice>())
        set(lan, "scope", CoroutineScope(SupervisorJob() + Dispatchers.Default))
        val session = jvmAllocate(LiveSession::class.java)
        val recording = MutableStateFlow<LocalRecording>(LocalRecording.Recording("synthetic"))
        val videoState = MutableStateFlow<RtcVideo?>(video)
        var stopCalls = 0
        val recorder = object : RecordingControl {
            override fun pause() = true
            override fun resume() = true
            override fun stop(): Boolean { stopCalls++; return true } // no finalization callback
            override fun abort(message: String) = Unit
        }
        set(session, "guard", Any()); set(session, "lan", lan); set(session, "_video", videoState)
        set(session, "_recording", recording); set(session, "recorder", recorder)
        set(session, "scope", CoroutineScope(SupervisorJob() + Dispatchers.Default))

        session.close()
        assertFalse(generation.isCurrent(originalGeneration))
        assertEquals(DirectPeerStop.State.PENDING, stop.state())
        assertEquals(1, stopCalls)
        assertSame(video, videoState.value) // recorder/camera owner remains until finalization
        assertTrue(recording.value is LocalRecording.Recording)
        assertTrue(queued.isNotEmpty()) // blocked executor: no native stop is claimed
    }
}
