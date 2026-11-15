package de.kontrakurs.kontraprompter.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.lang.reflect.InvocationTargetException
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import sun.misc.Unsafe

/** Exercises the actual approval/disconnect methods with a deliberately delayed send. */
class LanApprovalRaceTest {
    private fun set(target: Any, name: String, value: Any?) {
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
    }

    @Test fun `message before local approval is still rejected`() {
        val ctor = AuthenticatedChannel::class.java.declaredConstructors.single { it.parameterCount == 8 }.apply { isAccessible = true }
        val channel = ctor.newInstance(Socket(), DataInputStream(ByteArrayInputStream(byteArrayOf())), DataOutputStream(ByteArrayOutputStream()),
            ByteArray(16), ByteArray(32), ByteArray(32), "host", "test") as AuthenticatedChannel
        val unsafeField = Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val instance = (unsafeField.get(null) as Unsafe).allocateInstance(LanSession::class.java) as LanSession
        set(instance, "_state", MutableStateFlow(LanState(authenticated = true, remoteApproved = true)))
        set(instance, "closed", AtomicBoolean(false)); set(instance, "connected", AtomicBoolean(true))
        set(instance, "socketLock", Any()); set(instance, "deliveryLock", Any()); set(instance, "channel", channel)
        val method = LanSession::class.java.getDeclaredMethod("deliverApprovedMessage", AuthenticatedChannel::class.java, String::class.java, String::class.java)
            .apply { isAccessible = true }
        val thrown = assertThrows(InvocationTargetException::class.java) { method.invoke(instance, channel, "sdp", "synthetic") }
        assertTrue(thrown.cause is IllegalStateException)
    }

    @Test fun `disconnect wakes a message waiting for approval without delivering it`() {
        val ctor = AuthenticatedChannel::class.java.declaredConstructors.single { it.parameterCount == 8 }.apply { isAccessible = true }
        val channel = ctor.newInstance(Socket(), DataInputStream(ByteArrayInputStream(byteArrayOf())), DataOutputStream(ByteArrayOutputStream()),
            ByteArray(16), ByteArray(32), ByteArray(32), "host", "test") as AuthenticatedChannel
        val unsafeField = Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val instance = (unsafeField.get(null) as Unsafe).allocateInstance(LanSession::class.java) as LanSession
        val state = MutableStateFlow(LanState(authenticated = true, remoteApproved = true))
        val finished = CountDownLatch(1)
        val delivered = AtomicInteger()
        val rejected = AtomicBoolean(false)
        set(instance, "_state", state); set(instance, "closed", AtomicBoolean(false))
        set(instance, "readyDelivered", AtomicBoolean(false)); set(instance, "connected", AtomicBoolean(true))
        set(instance, "socketLock", Any()); set(instance, "deliveryLock", Any()); set(instance, "channel", channel)
        instance.onMessage = { _, _, _ -> delivered.incrementAndGet(); Unit }
        val pendingType = Class.forName("de.kontrakurs.kontraprompter.network.LanSession\$PendingApproval")
        val pending = pendingType.getDeclaredConstructor(AuthenticatedChannel::class.java).apply { isAccessible = true }.newInstance(channel)
        set(instance, "pendingApproval", pending)
        val waiter = Thread {
            try {
                LanSession::class.java.getDeclaredMethod("deliverApprovedMessage", AuthenticatedChannel::class.java, String::class.java, String::class.java)
                    .apply { isAccessible = true }.invoke(instance, channel, "sdp", "synthetic")
            } catch (e: InvocationTargetException) { rejected.set(e.cause is IllegalStateException) }
            finally { finished.countDown() }
        }.apply { isDaemon = true; start() }
        val limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (waiter.state != Thread.State.TIMED_WAITING && System.nanoTime() < limit) Thread.yield()
        assertEquals(Thread.State.TIMED_WAITING, waiter.state)
        LanSession::class.java.getDeclaredMethod("disconnect", String::class.java, AuthenticatedChannel::class.java)
            .apply { isAccessible = true }.invoke(instance, "test disconnect", channel)
        assertTrue(finished.await(5, TimeUnit.SECONDS))
        assertTrue(rejected.get())
        assertEquals(0, delivered.get())
        assertFalse(state.value.ready)
    }

    @Test fun `early authenticated SDP waits for local approval and ready callback`() {
        val flushing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val ready = CountDownLatch(1)
        val delivered = CountDownLatch(1)
        val disconnected = CountDownLatch(1)
        val localInput = PipedInputStream()
        val remoteOutput = PipedOutputStream(localInput)
        val remoteInput = PipedInputStream()
        val localWire = PipedOutputStream(remoteInput)
        val blockedOutput = object : OutputStream() {
            override fun write(b: Int) = localWire.write(b)
            override fun write(b: ByteArray, off: Int, len: Int) = localWire.write(b, off, len)
            override fun flush() { flushing.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        }
        val socket = object : Socket() { override fun close() { localInput.close() } }
        val ctor = AuthenticatedChannel::class.java.declaredConstructors.single { it.parameterCount == 8 }.apply { isAccessible = true }
        val key = ByteArray(16); val host = ByteArray(32); val client = ByteArray(32)
        val local = ctor.newInstance(socket, DataInputStream(localInput), DataOutputStream(blockedOutput), key, host, client, "host", "test") as AuthenticatedChannel
        val peer = ctor.newInstance(Socket(), DataInputStream(remoteInput), DataOutputStream(remoteOutput), key, host, client, "client", "test") as AuthenticatedChannel
        val unsafeField = Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val instance = (unsafeField.get(null) as Unsafe).allocateInstance(LanSession::class.java) as LanSession
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val state = MutableStateFlow(LanState())
        try {
            set(instance, "scope", scope); set(instance, "_state", state)
            set(instance, "closed", AtomicBoolean(false)); set(instance, "readyDelivered", AtomicBoolean(false))
            set(instance, "connected", AtomicBoolean(true)); set(instance, "socketLock", Any()); set(instance, "deliveryLock", Any()); set(instance, "channel", local)
            instance.onReady = { ready.countDown() }
            instance.onMessage = { _, type, _ -> if (type == "sdp" && ready.count == 0L) delivered.countDown() }
            instance.onDisconnect = { disconnected.countDown() }
            val reader = Thread {
                LanSession::class.java.getDeclaredMethod("authenticated", AuthenticatedChannel::class.java)
                    .apply { isAccessible = true }.invoke(instance, local)
            }.apply { isDaemon = true; start() }
            peer.send("approve", "")
            val limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!state.value.remoteApproved && System.nanoTime() < limit) Thread.yield()
            assertTrue(state.value.remoteApproved)
            instance.approve()
            assertTrue(flushing.await(5, TimeUnit.SECONDS))
            assertEquals("approve", peer.receive().first)
            peer.send("sdp", "synthetic")
            assertFalse("early SDP disconnected", disconnected.await(100, TimeUnit.MILLISECONDS))
            assertFalse("SDP arrived before local approval completed", delivered.await(100, TimeUnit.MILLISECONDS))
            release.countDown()
            assertTrue("SDP not delivered after ready", delivered.await(5, TimeUnit.SECONDS))
            assertEquals(0L, ready.count)
            remoteOutput.close()
            reader.join(1_000)
        } finally { release.countDown(); scope.cancel(); localInput.close(); remoteInput.close() }
    }

    @Test fun `active channel with both approvals becomes ready once`() {
        val ctor = AuthenticatedChannel::class.java.declaredConstructors.single { it.parameterCount == 8 }.apply { isAccessible = true }
        val channel = ctor.newInstance(Socket(), DataInputStream(ByteArrayInputStream(byteArrayOf())), DataOutputStream(ByteArrayOutputStream()),
            ByteArray(16), ByteArray(32), ByteArray(32), "host", "test") as AuthenticatedChannel
        val unsafeField = Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val instance = (unsafeField.get(null) as Unsafe).allocateInstance(LanSession::class.java) as LanSession
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val state = MutableStateFlow(LanState(authenticated = true, remoteApproved = true))
        val calls = AtomicInteger()
        val ready = CountDownLatch(1)
        try {
            set(instance, "scope", scope)
            set(instance, "_state", state)
            set(instance, "closed", AtomicBoolean(false))
            set(instance, "readyDelivered", AtomicBoolean(false))
            set(instance, "connected", AtomicBoolean(true))
            set(instance, "socketLock", Any()); set(instance, "deliveryLock", Any())
            set(instance, "channel", channel)
            instance.onReady = { calls.incrementAndGet(); ready.countDown() }
            instance.approve()
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            assertTrue(state.value.localApproved)
            assertTrue(state.value.remoteApproved)
            assertTrue(state.value.ready)
            LanSession::class.java.getDeclaredMethod("applyApproval", AuthenticatedChannel::class.java, Boolean::class.javaPrimitiveType)
                .apply { isAccessible = true }.invoke(instance, channel, false)
            assertEquals(1, calls.get())
        } finally { scope.cancel() }
    }

    @Test fun `late approval send cannot restore approval after disconnect`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val out = object : OutputStream() {
            override fun write(b: Int) { error("unexpected individual write") }
            override fun write(b: ByteArray, off: Int, len: Int) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
        }
        val socket = object : Socket() { override fun close() = Unit }
        val ctor = AuthenticatedChannel::class.java.declaredConstructors.single { it.parameterCount == 8 }.apply { isAccessible = true }
        val channel = ctor.newInstance(socket, DataInputStream(ByteArrayInputStream(byteArrayOf())), DataOutputStream(out),
            ByteArray(16), ByteArray(32), ByteArray(32), "host", "test") as AuthenticatedChannel
        val unsafeField = Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val instance = (unsafeField.get(null) as Unsafe).allocateInstance(LanSession::class.java) as LanSession
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val state = MutableStateFlow(LanState(authenticated = true, remoteApproved = true))
        try {
            set(instance, "scope", scope)
            set(instance, "_state", state)
            set(instance, "closed", AtomicBoolean(false))
            set(instance, "readyDelivered", AtomicBoolean(false))
            set(instance, "connected", AtomicBoolean(true))
            set(instance, "socketLock", Any()); set(instance, "deliveryLock", Any())
            set(instance, "channel", channel)
            instance.approve()
            assertTrue("approval send never started", entered.await(5, TimeUnit.SECONDS))
            val children = scope.coroutineContext[Job]!!.children.toList()
            assertEquals(1, children.size)
            LanSession::class.java.getDeclaredMethod("disconnect", String::class.java, AuthenticatedChannel::class.java)
                .apply { isAccessible = true }.invoke(instance, "test disconnect", channel)
            assertFalse(state.value.authenticated)
            // Simulate a remote approve message that was decoded before disconnect
            // but is processed only afterwards.
            LanSession::class.java.getDeclaredMethod("applyApproval", AuthenticatedChannel::class.java, Boolean::class.javaPrimitiveType)
                .apply { isAccessible = true }.invoke(instance, channel, false)
            assertFalse(state.value.remoteApproved)
            release.countDown()
            runBlocking { children.joinAll() }
            assertFalse("late approval changed disconnected state", state.value.localApproved)
            assertFalse(state.value.remoteApproved)
            assertFalse(state.value.ready)
        } finally {
            release.countDown()
            scope.cancel()
        }
    }
}
