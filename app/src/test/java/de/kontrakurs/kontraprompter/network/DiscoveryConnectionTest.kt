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
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import sun.misc.Unsafe

/** Real loopback TCP, with an intentionally incomplete authentication handshake. */
class DiscoveryConnectionTest {
    private fun set(target: Any, name: String, value: Any?) {
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
    }

    @Test fun `manual connection still authenticates on loopback`() {
        val unsafe = Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null) as Unsafe
        val lan = unsafe.allocateInstance(LanSession::class.java) as LanSession
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val state = MutableStateFlow(LanState(role = "client"))
        set(lan, "scope", scope); set(lan, "_state", state)
        set(lan, "closed", AtomicBoolean(false)); set(lan, "connected", AtomicBoolean(false))
        set(lan, "readyDelivered", AtomicBoolean(false)); set(lan, "socketLock", Any()); set(lan, "deviceName", "test")
        val token = "00000000000000000000000000000000"
        val peerChannel = AtomicReference<AuthenticatedChannel>()
        val peerReady = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
                server.soTimeout = 5_000
                val host = Thread {
                    try {
                        val socket = server.accept().apply { soTimeout = 5_000 }
                        peerChannel.set(AuthenticatedChannel.host(socket, token, "test-host"))
                        peerReady.countDown()
                        release.await(5, TimeUnit.SECONDS)
                    } finally { peerChannel.get()?.close() }
                }.apply { isDaemon = true; start() }
                lan.connect("127.0.0.1", server.localPort, token)
                assertTrue(peerReady.await(5, TimeUnit.SECONDS))
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (!state.value.authenticated && System.nanoTime() < deadline) Thread.yield()
                assertTrue(state.value.authenticated)
                assertEquals("test-host", state.value.remoteName)
                release.countDown()
                host.join(5_000)
            }
        } finally { release.countDown(); peerChannel.get()?.close(); scope.cancel() }
    }

    @Test fun `losing selection closes the authenticating socket and prevents installation`() {
        val unsafe = Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null) as Unsafe
        val lan = unsafe.allocateInstance(LanSession::class.java) as LanSession
        val item = unsafe.allocateInstance(FoundDevice::class.java) as FoundDevice
        val discovery = DiscoveryServices<FoundDevice>()
        discovery.found("camera", item)
        val selection = discovery.select(item)!!
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val state = MutableStateFlow(LanState(role = "client"))
        set(lan, "scope", scope); set(lan, "_state", state); set(lan, "discovered", discovery)
        set(lan, "closed", AtomicBoolean(false)); set(lan, "connected", AtomicBoolean(false))
        set(lan, "readyDelivered", AtomicBoolean(false)); set(lan, "socketLock", Any()); set(lan, "deviceName", "test")
        try {
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
                server.soTimeout = 5_000
                val endpoint = DiscoveredEndpoint("127.0.0.1", server.localPort, selection)
                lan.connectDiscovered(endpoint, "00000000000000000000000000000000")
                server.accept().use { peer ->
                    peer.soTimeout = 5_000
                    val jobs = scope.coroutineContext[Job]!!.children.toList()
                    assertTrue(jobs.isNotEmpty())
                    discovery.lost("camera")
                    // An update belonging to a new selection must not be overwritten
                    // by the old handshake's resulting socket exception.
                    state.value = LanState(role = "client", status = "new selection")
                    assertEquals(-1, peer.getInputStream().read())
                    runBlocking { jobs.joinAll() }
                    assertEquals("new selection", state.value.status)
                    assertFalse(state.value.authenticated)
                    assertFalse(state.value.ready)
                }
            }
        } finally { scope.cancel(); discovery.close() }
    }

    @Test fun `installed discovered connection survives later service loss`() {
        val unsafe = Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null) as Unsafe
        val lan = unsafe.allocateInstance(LanSession::class.java) as LanSession
        val item = unsafe.allocateInstance(FoundDevice::class.java) as FoundDevice
        val discovery = DiscoveryServices<FoundDevice>()
        discovery.found("camera", item)
        val selection = discovery.select(item)!!
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val state = MutableStateFlow(LanState(role = "client"))
        set(lan, "scope", scope); set(lan, "_state", state); set(lan, "discovered", discovery)
        set(lan, "closed", AtomicBoolean(false)); set(lan, "connected", AtomicBoolean(false))
        set(lan, "readyDelivered", AtomicBoolean(false)); set(lan, "socketLock", Any()); set(lan, "approvalLock", Any()); set(lan, "deviceName", "test")
        val token = "00000000000000000000000000000000"
        val peerChannel = AtomicReference<AuthenticatedChannel>()
        val peerReady = CountDownLatch(1); val release = CountDownLatch(1)
        try {
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
                server.soTimeout = 5_000
                val host = Thread {
                    try {
                        val socket = server.accept().apply { soTimeout = 5_000 }
                        peerChannel.set(AuthenticatedChannel.host(socket, token, "test-host"))
                        peerReady.countDown()
                        release.await(5, TimeUnit.SECONDS)
                    } finally { peerChannel.get()?.close() }
                }.apply { isDaemon = true; start() }
                lan.connectDiscovered(DiscoveredEndpoint("127.0.0.1", server.localPort, selection), token)
                assertTrue(peerReady.await(5, TimeUnit.SECONDS))
                var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (!state.value.authenticated && System.nanoTime() < deadline) Thread.yield()
                assertTrue(state.value.authenticated)
                discovery.lost("camera")
                peerChannel.get().send("approve", "")
                deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (!state.value.remoteApproved && System.nanoTime() < deadline) Thread.yield()
                assertTrue(state.value.remoteApproved)
                release.countDown(); host.join(5_000)
            }
        } finally { release.countDown(); peerChannel.get()?.close(); scope.cancel(); discovery.close() }
    }
}
