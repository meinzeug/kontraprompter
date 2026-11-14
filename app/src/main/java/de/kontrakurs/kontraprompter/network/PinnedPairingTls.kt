package de.kontrakurs.kontraprompter.network

import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager

/**
 * Experimental transport primitive for a later, separately authenticated pairing flow.
 * It is deliberately not connected to LanSession or the camera UI.
 */
internal class PinnedPairingTls(identity: KeyStore, password: CharArray?, peerSpkiSha256: ByteArray, identityAlias: String) {
    private val peerTrust = PinnedPeerTrust(peerSpkiSha256)
    private val context = SSLContext.getInstance("TLS").apply {
        init(arrayOf(SinglePairingKeyManager(identity, password, identityAlias)), arrayOf(peerTrust), null)
    }

    /** Returns a socket only after a full mutual TLS handshake has completed. */
    fun connect(address: InetAddress, port: Int, timeoutMs: Int): SSLSocket {
        require(port in 1..65535 && timeoutMs in 1..30_000)
        val socket = context.socketFactory.createSocket() as SSLSocket
        try {
            socket.useClientMode = true
            socket.enabledProtocols = tls13(socket.supportedProtocols)
            socket.soTimeout = timeoutMs
            socket.connect(InetSocketAddress(address, port), timeoutMs)
            socket.startHandshake()
            peerTrust.verifyEstablished(socket)
            PeerConfirmation.client(socket)
            return socket
        } catch (error: Exception) {
            runCatching { socket.close() }
            throw error
        }
    }

    /** Only the explicit pinned key is trusted; the listener never changes global TLS settings. */
    fun listen(address: InetAddress, port: Int, timeoutMs: Int): Listener {
        require(port in 0..65535 && timeoutMs in 1..30_000)
        val server = context.serverSocketFactory.createServerSocket() as SSLServerSocket
        try {
            server.useClientMode = false
            server.enabledProtocols = tls13(server.supportedProtocols)
            server.needClientAuth = true
            server.soTimeout = timeoutMs
            server.bind(InetSocketAddress(address, port))
            return Listener(server, timeoutMs, peerTrust)
        } catch (error: Exception) {
            runCatching { server.close() }
            throw error
        }
    }

    internal class Listener(private val server: SSLServerSocket, private val timeoutMs: Int, private val peerTrust: PinnedPeerTrust) : Closeable {
        private val lock = Any()
        private val pending = mutableSetOf<SSLSocket>()
        private var closed = false
        val localPort: Int get() = server.localPort
        fun accept(): SSLSocket {
            val socket = server.accept() as SSLSocket
            try {
                synchronized(lock) {
                    if (closed) throw IOException("Listener ist geschlossen")
                    pending.add(socket)
                }
                socket.useClientMode = false
                socket.needClientAuth = true
                socket.enabledProtocols = tls13(socket.supportedProtocols)
                socket.soTimeout = timeoutMs
                socket.startHandshake()
                peerTrust.verifyEstablished(socket)
                PeerConfirmation.server(socket)
                synchronized(lock) {
                    if (closed) throw IOException("Listener ist geschlossen")
                    pending.remove(socket)
                    return socket
                }
            } catch (error: Exception) {
                synchronized(lock) { pending.remove(socket) }
                runCatching { socket.close() }
                throw error
            }
        }
        override fun close() = synchronized(lock) {
            if (closed) return@synchronized
            closed = true
            try { server.close() } finally { pending.forEach { runCatching { it.close() } }; pending.clear() }
        }
    }

    private companion object {
        fun tls13(supported: Array<String>): Array<String> {
            if ("TLSv1.3" !in supported) throw IOException("TLS 1.3 ist auf diesem Gerät nicht verfügbar")
            return arrayOf("TLSv1.3")
        }
    }
}

/** Exposes exactly the pairing key, never other keys present in the Android Keystore. */
internal class SinglePairingKeyManager(identity: KeyStore, password: CharArray?, private val alias: String) : X509ExtendedKeyManager() {
    private val key: PrivateKey = (identity.getKey(alias, password) as? PrivateKey) ?: throw CertificateException("Pairing-Schlüssel fehlt")
    private val chain: Array<X509Certificate> = identity.getCertificateChain(alias)?.map {
        it as? X509Certificate ?: throw CertificateException("Ungültiges Pairing-Zertifikat")
    }?.toTypedArray()?.also {
        if (it.isEmpty() || it.size > 8 || key.algorithm != "EC" || it[0].publicKey.algorithm != "EC") throw CertificateException("Ungültiger Pairing-Schlüssel")
    } ?: throw CertificateException("Pairing-Zertifikat fehlt")

    private fun allowed(keyType: String?, issuers: Array<out Principal>?): Boolean =
        keyType == "EC" && (issuers.isNullOrEmpty() || chain.any { it.issuerX500Principal in issuers || it.subjectX500Principal in issuers })
    private fun select(keyType: String?, issuers: Array<out Principal>?) = if (allowed(keyType, issuers)) alias else null
    override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = select(keyType, issuers)?.let { arrayOf(it) }
    override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?): String? = keyType?.firstNotNullOfOrNull { select(it, issuers) }
    override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = select(keyType, issuers)?.let { arrayOf(it) }
    override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? = select(keyType, issuers)
    override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?): String? = chooseClientAlias(keyType, issuers, null)
    override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out Principal>?, engine: SSLEngine?): String? = select(keyType, issuers)
    override fun getCertificateChain(alias: String?): Array<X509Certificate>? = if (alias == this.alias) chain.copyOf() else null
    override fun getPrivateKey(alias: String?): PrivateKey? = if (alias == this.alias) key else null
}

/**
 * TLS 1.3 client handshake completion alone does not prove that the server has
 * accepted the client certificate. Confirm it over the authenticated TLS stream.
 */
internal object PeerConfirmation {
    private val request = byteArrayOf(0x4b, 0x50, 0x52, 0x01)
    private val response = byteArrayOf(0x4b, 0x50, 0x41, 0x01)
    private val finish = byteArrayOf(0x4b, 0x50, 0x46, 0x01)
    private val random = SecureRandom()
    private const val NONCE_SIZE = 32

    fun client(socket: SSLSocket) {
        val nonce = ByteArray(NONCE_SIZE).also(random::nextBytes)
        val input = DataInputStream(socket.inputStream)
        val output = socket.outputStream
        output.write(request); output.write(nonce); output.flush()
        val marker = ByteArray(response.size).also(input::readFully)
        val echoed = ByteArray(NONCE_SIZE).also(input::readFully)
        val peerNonce = ByteArray(NONCE_SIZE).also(input::readFully)
        if (!MessageDigest.isEqual(marker, response) || !MessageDigest.isEqual(echoed, nonce)) throw IOException("Gegenstelle hat die Verbindung nicht bestätigt")
        output.write(finish); output.write(peerNonce); output.flush()
    }

    fun server(socket: SSLSocket) {
        val input = DataInputStream(socket.inputStream)
        val output = socket.outputStream
        val marker = ByteArray(request.size).also(input::readFully)
        val peerNonce = ByteArray(NONCE_SIZE).also(input::readFully)
        if (!MessageDigest.isEqual(marker, request)) throw IOException("Ungültige Bestätigungsanfrage")
        val nonce = ByteArray(NONCE_SIZE).also(random::nextBytes)
        output.write(response); output.write(peerNonce); output.write(nonce); output.flush()
        val confirmation = ByteArray(finish.size).also(input::readFully)
        val echoed = ByteArray(NONCE_SIZE).also(input::readFully)
        if (!MessageDigest.isEqual(confirmation, finish) || !MessageDigest.isEqual(echoed, nonce)) throw IOException("Gegenstelle hat die Verbindung nicht bestätigt")
    }
}

/** Pinning is safe only when the pin was bound to the confirmed device beforehand. */
internal class PinnedPeerTrust(peerSpkiSha256: ByteArray) : X509TrustManager {
    private val pin = peerSpkiSha256.copyOf().also { require(it.size == 32) }
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = verify(chain, authType)
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = verify(chain, authType)

    // Providers may resume a TLS session without invoking the trust manager again.
    // Recheck the established peer identity and its current validity on every socket.
    fun verifyEstablished(socket: SSLSocket) {
        val chain = socket.session.peerCertificates.map {
            it as? X509Certificate ?: throw CertificateException("Ungültige Gegenstellenidentität")
        }.toTypedArray()
        verify(chain, chain.firstOrNull()?.publicKey?.algorithm)
    }

    private fun verify(chain: Array<out X509Certificate>?, authType: String?) {
        if (chain.isNullOrEmpty() || chain.size > 8 || authType.isNullOrBlank()) throw CertificateException("Ungültige Gegenstellenidentität")
        val leaf = chain[0]
        leaf.checkValidity()
        val key = leaf.publicKey?.encoded ?: throw CertificateException("Öffentlicher Schlüssel fehlt")
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(key)
        if (!MessageDigest.isEqual(pin, fingerprint)) throw CertificateException("Gegenstelle nicht gepinnt")
    }
}
