package de.kontrakurs.kontraprompter.network

import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class AuthenticatedChannelTest {
    @Test fun mutualProofAndAuthenticatedMessagesOnLoopback() {
        val token = PairingCrypto.token()
        ServerSocket(0).use { listener ->
            val executor = Executors.newSingleThreadExecutor()
            try {
                val host = executor.submit<String> {
                    listener.accept().use { socket ->
                        socket.soTimeout = 3000
                        AuthenticatedChannel.host(socket, token, "Kamera").use { channel ->
                            assertEquals("Anzeige", channel.remoteName)
                            assertEquals("offer" to "test-sdp", channel.receive())
                            channel.send("answer", "antwort")
                            "ok"
                        }
                    }
                }
                Socket("127.0.0.1", listener.localPort).use { socket ->
                    socket.soTimeout = 3000
                    // The camera displays uppercase groups; the user enters that exact format.
                    val displayedCode = token.chunked(4).joinToString(" ").uppercase()
                    AuthenticatedChannel.client(socket, displayedCode, "Anzeige").use { channel ->
                        assertEquals("Kamera", channel.remoteName)
                        channel.send("offer", "test-sdp")
                        assertEquals("answer" to "antwort", channel.receive())
                    }
                }
                assertEquals("ok", host.get(3, TimeUnit.SECONDS))
            } finally { executor.shutdownNow() }
        }
    }
    @Test fun wrongTokenCannotAuthenticate() {
        ServerSocket(0).use { listener ->
            val executor = Executors.newSingleThreadExecutor()
            try {
                val host = executor.submit<Boolean> { listener.accept().use { socket -> socket.soTimeout = 3000; runCatching { AuthenticatedChannel.host(socket, PairingCrypto.token(), "Kamera") }.isFailure } }
                Socket("127.0.0.1", listener.localPort).use { socket ->
                    socket.soTimeout = 3000
                    assertTrue(runCatching { AuthenticatedChannel.client(socket, PairingCrypto.token(), "Anzeige") }.isFailure)
                }
                assertTrue(host.get(3, TimeUnit.SECONDS))
            } finally { executor.shutdownNow() }
        }
    }
    @Test fun messageFieldsAreUnambiguousAndTokensAreStrict() {
        val key = PairingCrypto.parseToken("00112233-44556677-8899aabb-ccddeeff")
        assertFalse(PairingCrypto.equal(PairingCrypto.mac(key, "ab".toByteArray(), "c".toByteArray()), PairingCrypto.mac(key, "a".toByteArray(), "bc".toByteArray())))
        assertTrue(runCatching { PairingCrypto.parseToken("123") }.isFailure)
    }
    @Test fun replayedAuthenticatedPacketIsRejected() {
        val token = PairingCrypto.token()
        ServerSocket(0).use { destination ->
            ServerSocket(0).use { proxyListener ->
                val executor = Executors.newFixedThreadPool(2)
                try {
                    val host = executor.submit<Boolean> {
                        destination.accept().use { socket ->
                            socket.soTimeout = 3000
                            AuthenticatedChannel.host(socket, token, "Kamera").use { channel ->
                                assertEquals("offer" to "payload", channel.receive())
                                runCatching { channel.receive() }.isFailure
                            }
                        }
                    }
                    val proxy = executor.submit<Unit> {
                        proxyListener.accept().use { client ->
                            Socket("127.0.0.1", destination.localPort).use { server ->
                                client.soTimeout = 3000; server.soTimeout = 3000
                                val ci = java.io.DataInputStream(client.getInputStream()); val co = java.io.DataOutputStream(client.getOutputStream())
                                val si = java.io.DataInputStream(server.getInputStream()); val so = java.io.DataOutputStream(server.getOutputStream())
                                fun relay(input: java.io.DataInputStream, output: java.io.DataOutputStream) {
                                    val n = input.readInt(); val data = ByteArray(n); input.readFully(data); output.writeInt(n); output.write(data); output.flush()
                                }
                                repeat(3) { relay(si, co) } // protocol, host challenge, host name
                                repeat(3) { relay(ci, so) } // client challenge, name, proof
                                relay(si, co) // host proof
                                val bytes = java.io.ByteArrayOutputStream(); val packet = java.io.DataOutputStream(bytes)
                                packet.writeLong(ci.readLong()); repeat(3) { relay(ci, packet) }
                                so.write(bytes.toByteArray()); so.flush(); so.write(bytes.toByteArray()); so.flush()
                            }
                        }
                    }
                    Socket("127.0.0.1", proxyListener.localPort).use { socket ->
                        socket.soTimeout = 3000
                        AuthenticatedChannel.client(socket, token, "Anzeige").use { it.send("offer", "payload") }
                    }
                    proxy.get(3, TimeUnit.SECONDS)
                    assertTrue(host.get(3, TimeUnit.SECONDS))
                } finally { executor.shutdownNow() }
            }
        }
    }

}
