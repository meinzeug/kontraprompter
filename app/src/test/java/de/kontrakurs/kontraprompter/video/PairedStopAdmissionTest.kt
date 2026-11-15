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

/** Same test runs against the original and fixed LiveSession implementations. */
class PairedStopAdmissionTest {
    private fun set(target: Any, name: String, value: Any?) {
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
    }

    @Test fun `old stop cannot affect a preparing recording after disconnect`() {
        val lan = jvmAllocate(LanSession::class.java)
        val session = jvmAllocate(LiveSession::class.java)
        val ctor = AuthenticatedChannel::class.java.declaredConstructors.single { it.parameterCount == 8 }.apply { isAccessible = true }
        val marker = ctor.newInstance(Socket(), DataInputStream(ByteArrayInputStream(byteArrayOf())), DataOutputStream(ByteArrayOutputStream()),
            ByteArray(16), ByteArray(32), ByteArray(32), "host", "test") as AuthenticatedChannel
        val delivery = Any(); val id = UUID.randomUUID().toString()
        val lanState = MutableStateFlow(LanState(authenticated = true, localApproved = true, remoteApproved = true, ready = true))
        val recording = MutableStateFlow<LocalRecording>(LocalRecording.Preparing(id))
        set(lan, "socketLock", Any()); set(lan, "closed", AtomicBoolean(false)); set(lan, "connected", AtomicBoolean(true))
        runCatching { set(lan, "deliveryLock", delivery) }
        set(lan, "_state", lanState); set(lan, "channel", marker)
        set(session, "guard", Any()); set(session, "lan", lan); set(session, "_recording", recording)
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val done = CountDownLatch(1)
        val disconnectDone = CountDownLatch(1); val result = AtomicReference<Boolean>()
        val method = LiveSession::class.java.declaredMethods.firstOrNull { it.name.startsWith("stopPairedRecording") }
        try {
            Thread {
                try { synchronized(delivery) {
                    entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
                    result.set(if (method == null) session.stopRecording(id) else { method.isAccessible = true; method.invoke(session, marker, id) as Boolean })
                } } finally { done.countDown() }
            }.apply { isDaemon = true; start() }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            Thread {
                try { LanSession::class.java.getDeclaredMethod("disconnect", String::class.java, AuthenticatedChannel::class.java)
                    .apply { isAccessible = true }.invoke(lan, "test disconnect", marker) }
                finally { disconnectDone.countDown() }
            }.apply { isDaemon = true; start() }
            val limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (lanState.value.ready && System.nanoTime() < limit) Thread.yield()
            assertFalse(lanState.value.ready)
            release.countDown()
            assertTrue(done.await(5, TimeUnit.SECONDS)); assertTrue(disconnectDone.await(5, TimeUnit.SECONDS))
            assertFalse("old channel stopped the recording after disconnect", result.get())
            val stop = LiveSession::class.java.getDeclaredField("stopWhenStarted").apply { isAccessible = true }.getBoolean(session)
            assertFalse(stop)
            assertEquals(LocalRecording.Preparing(id), recording.value)
        } finally { release.countDown() }
    }
}
