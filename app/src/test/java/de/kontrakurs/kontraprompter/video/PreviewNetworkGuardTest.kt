package de.kontrakurs.kontraprompter.video

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

class PreviewNetworkGuardTest {
    @Test fun `route change invalidates even when interface and addresses stay identical`() {
        val original = PreviewCandidatePolicy.LinkSnapshot("wlan0", setOf("192.168.1.3/24"), setOf("default via 192.168.1.1"))
        val guard = PreviewNetworkGuard(original) { }
        guard.observe(original.copy(routes = setOf("default via 192.168.1.254")))
        assertFalse(guard.isValid())
        guard.observe(original)
        assertFalse(guard.isValid())
    }

    @Test fun `transient network change remains invalid after the original returns`() {
        val closures = AtomicInteger()
        val guard = PreviewNetworkGuard("wifi-route-a") { closures.incrementAndGet() }
        guard.observe("wifi-route-a")
        assertTrue(guard.isValid())
        guard.observe("wifi-route-b")
        guard.observe("wifi-route-a")
        assertFalse(guard.isValid())
        assertEquals(1, closures.get())
    }

    @Test fun `simultaneous lost and changed events schedule only one closure`() {
        val closures = AtomicInteger()
        val guard = PreviewNetworkGuard("wifi") { closures.incrementAndGet() }
        val start = CountDownLatch(1)
        val threads = List(16) { Thread { start.await(); guard.invalidate() }.apply { start() } }
        start.countDown()
        threads.forEach { it.join() }
        assertFalse(guard.isValid())
        assertEquals(1, closures.get())
    }
}
