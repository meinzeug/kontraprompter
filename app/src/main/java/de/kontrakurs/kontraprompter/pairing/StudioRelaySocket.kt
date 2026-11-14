package de.kontrakurs.kontraprompter.pairing

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Supplies an app-local socket to the existing authenticated channel while the
 * other end is bridged to a studio-session-bound relay. No ADB or LAN listener
 * is used. The relay endpoint must provide bounded, ordered, acknowledged frames
 * and recheck the authenticated studio sessions on every network operation.
 * The server may read signaling in this explicitly server-trusted mode.
 */
internal class StudioRelaySocket private constructor(val socket: Socket, private val bridge: RelayByteBridge) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    override fun close() {
        if (closed.compareAndSet(false, true)) { runCatching { socket.close() }; bridge.close() }
    }

    companion object {
        /** Local socket is returned only after a matching loopback pair exists. */
        fun open(endpoint: RelayByteEndpoint, onFailure: () -> Unit = {}): StudioRelaySocket {
            val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
            val listener = ServerSocket()
            var appSocket: Socket? = null
            var bridgeSocket: Socket? = null
            try {
                listener.reuseAddress = false
                listener.bind(InetSocketAddress(loopback, 0), 1)
                listener.soTimeout = 3_000
                appSocket = Socket().also { it.connect(InetSocketAddress(loopback, listener.localPort), 3_000) }
                val expectedPort = appSocket.localPort
                val deadline = System.nanoTime() + 3_000_000_000L
                var acceptedCount = 0
                while (bridgeSocket == null) {
                    if (++acceptedCount > 8) throw IOException("Zu viele lokale Relayverbindungen")
                    val remaining = deadline - System.nanoTime()
                    if (remaining <= 0) throw IOException("Lokaler Relaykanal nicht verfügbar")
                    listener.soTimeout = ((remaining + 999_999) / 1_000_000).coerceAtMost(3_000).toInt().coerceAtLeast(1)
                    val accepted = listener.accept()
                    if (accepted.inetAddress.isLoopbackAddress && accepted.inetAddress == appSocket.localAddress && accepted.port == expectedPort) bridgeSocket = accepted
                    else accepted.close()
                }
                check(appSocket.inetAddress.isLoopbackAddress && bridgeSocket.localAddress.isLoopbackAddress)
                val bridge = RelayByteBridge(bridgeSocket, endpoint, onFailure)
                bridge.start()
                return StudioRelaySocket(appSocket, bridge)
            } catch (error: Exception) {
                runCatching { appSocket?.close() }; runCatching { bridgeSocket?.close() }; runCatching { endpoint.close() }
                throw error
            } finally { runCatching { listener.close() } }
        }
    }
}
