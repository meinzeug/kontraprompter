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

/** Real loopback TCP, with an intentionally incomplete authentication handshake. */
class DiscoveryConnectionTest {
    private fun <T> allocate(type: Class<T>): T {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        return type.cast(unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, type))!!
    }

    private fun set(target: Any, name: String, value: Any?) {
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
    }

    @Test fun `resolved endpoint is applied before loading clears and cannot apply twice`() {
        val lan = allocate(LanSession::class.java)
        val item = allocate(FoundDevice::class.java)
        val discovery = DiscoveryServices<FoundDevice>()
        discovery.found("camera", item); val selection = discovery.select(item)!!
        val state = MutableStateFlow(LanState(role = "client", discoveryResolving = true))
        set(lan, "_state", state); set(lan, "discovered", discovery); set(lan, "closed", AtomicBoolean(false)); set(lan, "discoveryEventLock", Any())
        val endpoint = DiscoveredEndpoint("127.0.0.1", 1234, selection)
        var applied = 0
        assertTrue(lan.acceptResolved(endpoint) { assertTrue(state.value.discoveryResolving); applied++ })
        assertFalse(state.value.discoveryResolving)
        assertEquals(1, applied)
        assertFalse(lan.acceptResolved(endpoint) { applied++ })
        assertEquals(1, applied)
    }

    @Test fun `resolve timeout clears loading and rejects a later endpoint`() {
        val lan = allocate(LanSession::class.java)
        val item = allocate(FoundDevice::class.java)
        val discovery = DiscoveryServices<FoundDevice>()
        discovery.found("camera", item); val selection = discovery.select(item)!!
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val state = MutableStateFlow(LanState(role = "client", discoveryResolving = true))
        set(lan, "scope", scope); set(lan, "_state", state); set(lan, "discovered", discovery)
        set(lan, "closed", AtomicBoolean(false)); set(lan, "discoveryEventLock", Any())
        try {
            LanSession::class.java.getDeclaredMethod("scheduleResolutionTimeout", DiscoveryServices.Selection::class.java, Long::class.javaPrimitiveType)
                .apply { isAccessible = true }.invoke(lan, selection, 30L)
            val limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (state.value.discoveryResolving && System.nanoTime() < limit) Thread.yield()
            assertFalse(state.value.discoveryResolving)
            assertTrue(state.value.error?.contains("dauert zu lange") == true)
            var applied = false
            assertFalse(lan.acceptResolved(DiscoveredEndpoint("127.0.0.1", 1234, selection)) { applied = true })
            assertFalse(applied)
        } finally { scope.cancel() }
    }

    @Test fun `old timeout cannot end a newer selection`() {
        val lan = allocate(LanSession::class.java)
        val first = allocate(FoundDevice::class.java)
        val second = allocate(FoundDevice::class.java)
        val discovery = DiscoveryServices<FoundDevice>()
        discovery.found("first", first); discovery.found("second", second)
        val old = discovery.select(first)!!
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val state = MutableStateFlow(LanState(role = "client", discoveryResolving = true))
        set(lan, "scope", scope); set(lan, "_state", state); set(lan, "discovered", discovery); set(lan, "closed", AtomicBoolean(false))
        try {
            LanSession::class.java.getDeclaredMethod("scheduleResolutionTimeout", DiscoveryServices.Selection::class.java, Long::class.javaPrimitiveType)
                .apply { isAccessible = true }.invoke(lan, old, 500L)
            val tasks = scope.coroutineContext[Job]!!.children.toList()
            assertEquals(1, tasks.size)
            discovery.select(second)
            runBlocking { tasks.joinAll() }
            assertTrue(state.value.discoveryResolving)
            assertNull(state.value.error)
        } finally { scope.cancel() }
    }

    @Test fun `resolve failure clears loading only for the selected generation`() {
        val lan = allocate(LanSession::class.java)
        val first = allocate(FoundDevice::class.java)
        val second = allocate(FoundDevice::class.java)
        val discovery = DiscoveryServices<FoundDevice>()
        discovery.found("first", first); discovery.found("second", second)
        val old = discovery.select(first)!!
        val current = discovery.select(second)!!
        val state = MutableStateFlow(LanState(role = "client", discoveryResolving = true))
        set(lan, "_state", state); set(lan, "discovered", discovery); set(lan, "closed", AtomicBoolean(false))
        val method = LanSession::class.java.getDeclaredMethod("resolutionFailed", DiscoveryServices.Selection::class.java, String::class.java)
            .apply { isAccessible = true }
        method.invoke(lan, old, "old failure")
        assertTrue(state.value.discoveryResolving)
        assertNull(state.value.error)
        method.invoke(lan, current, "current failure")
        assertFalse(state.value.discoveryResolving)
        assertEquals("current failure", state.value.error)
    }

    @Test fun `service loss ends loading only for the invalidated selection`() {
        val loading = LanState(role = "client", discoveryResolving = true)
        val unrelated = discoveryDevicesChanged(loading, emptyList(), false, "lost")
        assertTrue(unrelated.discoveryResolving)
        assertNull(unrelated.error)
        val lost = discoveryDevicesChanged(loading, emptyList(), true, "lost")
        assertFalse(lost.discoveryResolving)
        assertEquals("lost", lost.error)
    }

    @Test fun `discovery invalidation cannot cancel a concurrent manual connection`() {
        val lan = allocate(LanSession::class.java)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val state = MutableStateFlow(LanState(role = "client"))
        val item = allocate(FoundDevice::class.java)
        val discovery = DiscoveryServices<FoundDevice>()
        discovery.found("old camera", item); val selection = discovery.select(item)!!
        set(lan, "scope", scope); set(lan, "_state", state)
        set(lan, "discovered", discovery); set(lan, "discoveryEventLock", Any())
        set(lan, "closed", AtomicBoolean(false)); set(lan, "connected", AtomicBoolean(false))
        set(lan, "readyDelivered", AtomicBoolean(false)); set(lan, "socketLock", Any()); set(lan, "deviceName", "test")
        val token = "00000000000000000000000000000000"
        val peerChannel = AtomicReference<AuthenticatedChannel>()
        val peerReady = CountDownLatch(1)
        val accepted = CountDownLatch(1); val continueHandshake = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
                server.soTimeout = 5_000
                val host = Thread {
                    try {
                        val socket = server.accept().apply { soTimeout = 5_000 }
                        accepted.countDown()
                        check(continueHandshake.await(5, TimeUnit.SECONDS))
                        peerChannel.set(AuthenticatedChannel.host(socket, token, "test-host"))
                        peerReady.countDown()
                        release.await(5, TimeUnit.SECONDS)
                    } finally { peerChannel.get()?.close() }
                }.apply { isDaemon = true; start() }
                lan.connect("127.0.0.1", server.localPort, token)
                assertTrue(accepted.await(5, TimeUnit.SECONDS))
                // A second connection cannot seize the first attempt's slot.
                lan.connectDiscovered(DiscoveredEndpoint("127.0.0.1", server.localPort, selection), token)
                lan.cancelResolutionSelection()
                continueHandshake.countDown()
                assertTrue(peerReady.await(5, TimeUnit.SECONDS))
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (!state.value.authenticated && System.nanoTime() < deadline) Thread.yield()
                assertTrue(state.value.authenticated)
                assertEquals("test-host", state.value.remoteName)
                release.countDown()
                host.join(5_000)
            }
        } finally { continueHandshake.countDown(); release.countDown(); peerChannel.get()?.close(); scope.cancel(); discovery.close() }
    }

    @Test fun `losing selection closes the authenticating socket and prevents installation`() {
        val lan = allocate(LanSession::class.java)
        val item = allocate(FoundDevice::class.java)
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
        val lan = allocate(LanSession::class.java)
        val item = allocate(FoundDevice::class.java)
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
