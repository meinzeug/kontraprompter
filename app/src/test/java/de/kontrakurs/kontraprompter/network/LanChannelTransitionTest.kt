package de.kontrakurs.kontraprompter.network

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
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import sun.misc.Unsafe

/** Exercises the real receive callback and install/disconnect transitions with synthetic sockets. */
class LanChannelTransitionTest {
    private fun set(target: Any, name: String, value: Any?) {
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
    }

    @Test fun `old message cannot act after a new ready channel is installed`() {
        val input = PipedInputStream()
        val peerOutput = PipedOutputStream(input)
        val oldSocket = object : Socket() { override fun close() = Unit }
        val ctor = AuthenticatedChannel::class.java.declaredConstructors.single { it.parameterCount == 8 }.apply { isAccessible = true }
        val key = ByteArray(16); val host = ByteArray(32); val client = ByteArray(32)
        val old = ctor.newInstance(oldSocket, DataInputStream(input), DataOutputStream(ByteArrayOutputStream()), key, host, client, "host", "old") as AuthenticatedChannel
        val peer = ctor.newInstance(Socket(), DataInputStream(ByteArrayInputStream(byteArrayOf())), DataOutputStream(peerOutput), key, host, client, "client", "old") as AuthenticatedChannel
        val newSocket = Socket()
        val next = ctor.newInstance(newSocket, DataInputStream(ByteArrayInputStream(byteArrayOf())), DataOutputStream(ByteArrayOutputStream()), key, host, client, "host", "new") as AuthenticatedChannel
        val unsafe = Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null) as Unsafe
        val lan = unsafe.allocateInstance(LanSession::class.java) as LanSession
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val state = MutableStateFlow(LanState(authenticated = true, localApproved = true, remoteApproved = true, ready = true))
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val messageDone = CountDownLatch(1)
        val installed = CountDownLatch(1); val disconnectDone = CountDownLatch(1)
        val firstInstall = AtomicBoolean(false)
        val staleEffects = AtomicInteger()
        var reader: Thread? = null
        try {
            set(lan, "scope", scope); set(lan, "_state", state)
            set(lan, "closed", AtomicBoolean(false)); set(lan, "readyDelivered", AtomicBoolean(true))
            set(lan, "connected", AtomicBoolean(true)); set(lan, "socketLock", Any())
            // Keep the test executable against the original class, which did not have this lock.
            runCatching { set(lan, "deliveryLock", Any()) }
            runCatching { set(lan, "approvalLock", Any()) }
            set(lan, "channel", old)
            val effect = {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                // Mirrors the existing capture dispatcher: it reads the *current* ready state
                // immediately before executing an action, without an old-channel marker.
                if (state.value.ready) staleEffects.incrementAndGet()
                messageDone.countDown()
            }
            val callbackField = LanSession::class.java.getDeclaredField("onMessage").apply { isAccessible = true }
            if (callbackField.type.name == "kotlin.jvm.functions.Function3")
                callbackField.set(lan, { _: Any, _: String, _: String -> effect() })
            else callbackField.set(lan, { _: String, _: String -> effect() })
            reader = Thread {
                LanSession::class.java.getDeclaredMethod("authenticated", AuthenticatedChannel::class.java)
                    .apply { isAccessible = true }.invoke(lan, old)
            }.apply { isDaemon = true; start() }
            peer.send("sdp", "synthetic")
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val disconnect = Thread {
                try {
                    LanSession::class.java.getDeclaredMethod("disconnect", String::class.java, AuthenticatedChannel::class.java)
                        .apply { isAccessible = true }.invoke(lan, "test disconnect", old)
                } finally { disconnectDone.countDown() }
            }.apply { isDaemon = true; start() }
            val limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (state.value.ready && System.nanoTime() < limit) Thread.yield()
            assertFalse(state.value.ready)
            set(lan, "pendingSocket", newSocket)
            val installThread = Thread {
                val ok = LanSession::class.java.getDeclaredMethod("install", AuthenticatedChannel::class.java, Socket::class.java)
                    .apply { isAccessible = true }.invoke(lan, next, newSocket) as Boolean
                if (ok) {
                    firstInstall.set(true)
                    state.value = LanState(authenticated = true, localApproved = true, remoteApproved = true, ready = true)
                    installed.countDown()
                }
            }.apply { isDaemon = true; start() }
            // Baseline installed the new ready channel while the old callback was paused.
            assertFalse("new channel became ready before the old callback finished", installed.await(250, TimeUnit.MILLISECONDS))
            release.countDown()
            assertTrue(disconnectDone.await(5, TimeUnit.SECONDS))
            installThread.join(5_000)
            assertFalse(installThread.isAlive)
            assertTrue(messageDone.await(5, TimeUnit.SECONDS))
            assertEquals("old callback acted against a new ready session", 0, staleEffects.get())
            if (!firstInstall.get()) {
                // A transition-time rejection must permit a fresh manual attempt
                // once the old disconnect callback has completed.
                val retrySocket = Socket()
                val retry = ctor.newInstance(retrySocket, DataInputStream(ByteArrayInputStream(byteArrayOf())), DataOutputStream(ByteArrayOutputStream()), key, host, client, "host", "retry") as AuthenticatedChannel
                set(lan, "pendingSocket", retrySocket)
                val retryResult = LanSession::class.java.getDeclaredMethod("install", AuthenticatedChannel::class.java, Socket::class.java)
                    .apply { isAccessible = true }.invoke(lan, retry, retrySocket) as Boolean
                assertTrue("fresh attempt rejected after disconnect completed", retryResult)
            }
        } finally {
            release.countDown(); scope.cancel(); peerOutput.close(); input.close()
            reader?.join(1_000)
        }
    }
}
