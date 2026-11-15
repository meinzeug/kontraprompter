package de.kontrakurs.kontraprompter.network

import org.junit.Assert.*
import org.junit.Test
import java.io.FilterInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Models Android's documented SocketAdaptor blockingLock; not an Android runtime test. */
class AndroidSocketAdapterModelTest {
    private class SharedLockSocket(private val delegate: Socket) : Socket() {
        private val lock = Any()
        val armed = AtomicBoolean(false)
        val reading = CountDownLatch(1)
        override fun getInputStream(): InputStream = object : FilterInputStream(delegate.getInputStream()) {
            override fun read(): Int = synchronized(lock) {
                if (armed.get()) reading.countDown()
                `in`.read()
            }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = synchronized(lock) {
                if (armed.get()) reading.countDown()
                `in`.read(buffer, offset, length)
            }
        }
        override fun getOutputStream(): OutputStream {
            val output = delegate.getOutputStream()
            return object : OutputStream() {
                override fun write(value: Int) = synchronized(lock) { output.write(value) }
                override fun write(buffer: ByteArray, offset: Int, length: Int) = synchronized(lock) { output.write(buffer, offset, length) }
                override fun flush() = synchronized(lock) { output.flush() }
            }
        }
        override fun close() = delegate.close()
    }

    @Test fun `shared adapter lock delays authenticated write until a read completes`() {
        val token = "00000000000000000000000000000000"
        val workers = Executors.newFixedThreadPool(3)
        val wake = CountDownLatch(1)
        try {
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
                server.soTimeout = 5_000
                val host = workers.submit {
                    server.accept().use { socket ->
                        socket.soTimeout = 5_000
                        val peer = AuthenticatedChannel.host(socket, token, "host")
                        check(wake.await(5, TimeUnit.SECONDS))
                        peer.send("wake", "synthetic")
                        val message = peer.receive()
                        check(message.first == "ping" && message.second == "synthetic")
                    }
                }
                val wrapped = SharedLockSocket(Socket("127.0.0.1", server.localPort).apply { soTimeout = 5_000 })
                try {
                    val client = AuthenticatedChannel.client(wrapped, token, "client")
                    wrapped.armed.set(true)
                    val reader = workers.submit<Pair<String, String>> { client.receive() }
                    assertTrue(wrapped.reading.await(5, TimeUnit.SECONDS))
                    val sent = CountDownLatch(1)
                    val writer = workers.submit { client.send("ping", "synthetic"); sent.countDown() }
                    assertFalse("model should block write behind an outstanding read", sent.await(150, TimeUnit.MILLISECONDS))
                    wake.countDown()
                    assertEquals("wake", reader.get(5, TimeUnit.SECONDS).first)
                    assertTrue(sent.await(5, TimeUnit.SECONDS))
                    writer.get(5, TimeUnit.SECONDS); host.get(5, TimeUnit.SECONDS)
                } finally { wrapped.close() }
            }
        } finally { wake.countDown(); workers.shutdownNow() }
    }
}
