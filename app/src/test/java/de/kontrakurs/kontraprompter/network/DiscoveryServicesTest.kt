package de.kontrakurs.kontraprompter.network

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.net.Socket

class DiscoveryServicesTest {
    @Test fun `android service types accept only local and unqualified DNS SD forms`() {
        listOf("_kontraprompter._tcp", "_kontraprompter._tcp.", "_kontraprompter._tcp.local", "_kontraprompter._tcp.local.", "_KontraPrompter._TCP.LOCAL.")
            .forEach { assertTrue("expected valid service type: $it", isKontraPrompterServiceType(it)) }
        listOf(null, "", "_kontraprompter._udp.local", "_other._tcp.local", "_kontraprompter._tcp.local.evil", "_kontraprompter._tcp.local..", " _kontraprompter._tcp.local")
            .forEach { assertFalse("expected invalid service type: $it", isKontraPrompterServiceType(it)) }
    }

    @Test fun `service name is session specific and safe for a DNS label`() {
        val first = discoveryServiceName("SM-A526B", ByteArray(8))
        val second = discoveryServiceName("SM-A526B", ByteArray(8) { 1 })
        assertNotEquals(first, second)
        assertTrue(first.toByteArray(Charsets.UTF_8).size <= 63)
        val unusual = discoveryServiceName("📱 ä".repeat(30), ByteArray(8))
        assertTrue(unusual.matches(Regex("[A-Za-z0-9_-]+")))
        assertTrue(unusual.toByteArray(Charsets.UTF_8).size <= 63)
        assertThrows(IllegalArgumentException::class.java) { discoveryServiceName("phone", ByteArray(7)) }
    }

    @Test fun `latest selection rejects old delayed resolve and posted UI update`() {
        val services = DiscoveryServices<Any>()
        val first = Any(); val second = Any()
        services.found("first", first); services.found("second", second)
        val one = services.select(first)!!
        assertTrue(services.current(one))
        val two = services.select(second)!!
        assertFalse(services.current(one))
        assertTrue(services.current(two))
        services.lost("second")
        assertFalse(services.current(two))
    }

    @Test fun `replaced entry invalidates its old result even with identical service name`() {
        val services = DiscoveryServices<Any>()
        val old = Any(); val replacement = Any()
        services.found("same", old)
        val lease = services.select(old)!!
        services.found("same", replacement)
        assertFalse(services.current(lease))
        assertNull(services.select(old))
        assertTrue(services.current(services.select(replacement)!!))
    }

    @Test fun `queued resolve callback is rejected after another selection`() {
        val services = DiscoveryServices<Any>()
        val old = Any(); val current = Any()
        services.found("old", old); services.found("current", current)
        val oldSelection = services.select(old)!!
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val done = CountDownLatch(1)
        val accepted = AtomicBoolean(true)
        Thread {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            accepted.set(services.current(oldSelection))
            done.countDown()
        }.apply { isDaemon = true; start() }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val newSelection = services.select(current)!!
        release.countDown()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertFalse(accepted.get())
        assertTrue(services.current(newSelection))
    }

    @Test fun `close prevents any further use or discovery`() {
        val services = DiscoveryServices<Any>()
        val item = Any(); services.found("test", item)
        val selected = services.select(item)!!
        services.close()
        assertFalse(services.current(selected))
        assertNull(services.select(item))
        assertTrue(services.found("later", Any()).isEmpty())
    }

    @Test fun `manual address editing cancels a pending resolve result`() {
        val services = DiscoveryServices<Any>()
        val item = Any(); services.found("camera", item)
        val selected = services.select(item)!!
        services.clearSelection()
        assertFalse(services.current(selected))
        assertTrue(services.current(services.select(item)!!))
    }

    @Test fun `cached old port cannot replace the selected new session result`() {
        data class Entry(val name: String, val port: Int)
        val old = Entry(discoveryServiceName("Xiaomi", ByteArray(8)), 45779)
        val fresh = Entry(discoveryServiceName("Xiaomi", ByteArray(8) { 1 }), 39531)
        val cache = mapOf(old.name to old.port, fresh.name to fresh.port)
        val services = DiscoveryServices<Entry>()
        services.found(old.name, old)
        val oldResolve = services.select(old)!!
        services.found(fresh.name, fresh)
        val freshResolve = services.select(fresh)!!
        var visiblePort = cache.getValue(fresh.name)
        if (services.current(oldResolve)) visiblePort = cache.getValue(old.name)
        assertEquals(39531, visiblePort)
        assertTrue(services.current(freshResolve))
    }

    @Test fun `selection change closes its pending socket and forbids late install`() {
        val services = DiscoveryServices<Any>()
        val first = Any(); val second = Any()
        services.found("first", first); services.found("second", second)
        val old = services.select(first)!!
        val closed = AtomicBoolean(false)
        val socket = object : Socket() { override fun close() { closed.set(true) } }
        assertTrue(services.attach(old, socket))
        val next = services.select(second)!!
        assertTrue(closed.get())
        assertNull(services.ifCurrent(old) { "late handshake installed" })
        assertEquals("new", services.ifCurrent(next) { "new" })
    }

    @Test fun `installed socket detaches and survives later discovery loss`() {
        val services = DiscoveryServices<Any>()
        val camera = Any(); services.found("camera", camera)
        val selection = services.select(camera)!!
        val closed = AtomicBoolean(false)
        val socket = object : Socket() { override fun close() { closed.set(true) } }
        assertTrue(services.attach(selection, socket))
        assertEquals(true, services.ifCurrent(selection) { services.detach(selection, socket); true })
        services.lost("camera")
        assertFalse(closed.get())
    }
}
