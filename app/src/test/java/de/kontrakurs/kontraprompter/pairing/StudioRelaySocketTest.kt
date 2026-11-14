package de.kontrakurs.kontraprompter.pairing

import de.kontrakurs.kontraprompter.network.AuthenticatedChannel
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class StudioRelaySocketTest {
    private class FakeEndpoint(private val incoming: LinkedBlockingQueue<RelayByteEndpoint.Frame>, private val outgoing: LinkedBlockingQueue<RelayByteEndpoint.Frame>) : RelayByteEndpoint {
        private val closed = AtomicBoolean(false)
        override fun send(sequence: Long, bytes: ByteArray) { check(!closed.get()); check(outgoing.offer(RelayByteEndpoint.Frame(sequence, bytes))) }
        override fun receive(): RelayByteEndpoint.Frame? = if (closed.get()) null else incoming.poll(30, TimeUnit.MILLISECONDS)
        override fun acknowledge(sequence: Long) { check(sequence > 0) }
        override fun close() { closed.set(true) }
    }

    @Test fun existingMutualHmacChannelWorksOverLocalRelaySockets() {
        val ab = LinkedBlockingQueue<RelayByteEndpoint.Frame>(); val ba = LinkedBlockingQueue<RelayByteEndpoint.Frame>()
        val pool = Executors.newSingleThreadExecutor()
        val camera = StudioRelaySocket.open(FakeEndpoint(ba, ab))
        val prompter = StudioRelaySocket.open(FakeEndpoint(ab, ba))
        try {
            camera.socket.soTimeout = 3_000; prompter.socket.soTimeout = 3_000
            val token = "0123456789abcdef0123456789abcdef"
            val server = pool.submit<AuthenticatedChannel> { AuthenticatedChannel.host(camera.socket, token, "camera") }
            val client = AuthenticatedChannel.client(prompter.socket, token, "prompter")
            val host = server.get(3, TimeUnit.SECONDS)
            client.send("capture_query", "hello")
            assertEquals("capture_query" to "hello", host.receive())
            host.send("capture_status", "ok")
            assertEquals("capture_status" to "ok", client.receive())
            client.close(); host.close()
        } finally { camera.close(); prompter.close(); pool.shutdownNow() }
    }
    @Test fun wrongSessionCodeNeverAuthenticates() {
        val ab = LinkedBlockingQueue<RelayByteEndpoint.Frame>(); val ba = LinkedBlockingQueue<RelayByteEndpoint.Frame>()
        val pool = Executors.newSingleThreadExecutor()
        val camera = StudioRelaySocket.open(FakeEndpoint(ba, ab))
        val prompter = StudioRelaySocket.open(FakeEndpoint(ab, ba))
        try {
            camera.socket.soTimeout = 3_000; prompter.socket.soTimeout = 3_000
            val server = pool.submit<AuthenticatedChannel> { AuthenticatedChannel.host(camera.socket, "0".repeat(32), "camera") }
            assertThrows(Exception::class.java) { AuthenticatedChannel.client(prompter.socket, "1".repeat(32), "prompter") }
            assertThrows(Exception::class.java) { server.get(3, TimeUnit.SECONDS) }
        } finally { camera.close(); prompter.close(); pool.shutdownNow() }
    }

}
