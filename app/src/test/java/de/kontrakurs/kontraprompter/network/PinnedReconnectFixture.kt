package de.kontrakurs.kontraprompter.network

import java.net.InetAddress
import javax.net.ssl.SSLSocket

/**
 * TEST-ONLY lifecycle experiment. Only a successful PinnedPairingTls handshake
 * may produce a candidate. The already trusted pin is supplied to that TLS
 * instance beforehand; this fixture does not establish or persist trust.
 *
 * Closing invalidates a pending attempt and closes an installed socket, but
 * cannot cancel PinnedPairingTls.connect while it is blocked. Callers must not
 * treat a returned raw socket as a production command-authorization lease.
 */
internal class PinnedReconnectFixture(private val tls: PinnedPairingTls) : AutoCloseable {
    private val lock = Any()
    private var generation = 0L
    private var revoked = false
    private var active: SSLSocket? = null

    fun connect(address: InetAddress, port: Int, timeoutMs: Int, beforeInstall: () -> Unit = {}): SSLSocket? {
        val attempt: Long
        val old: SSLSocket?
        synchronized(lock) {
            if (revoked) return null
            check(generation < Long.MAX_VALUE)
            attempt = ++generation
            old = active
            active = null
        }
        old?.let { runCatching { it.close() } }
        val candidate = tls.connect(address, port, timeoutMs)
        try {
            beforeInstall()
            val installed = synchronized(lock) {
                if (revoked || generation != attempt) false
                else { active = candidate; true }
            }
            if (installed) return candidate
            return null
        } finally {
            val retained = synchronized(lock) { active === candidate }
            if (!retained) runCatching { candidate.close() }
        }
    }

    fun disconnect() = invalidate(revoke = false)
    fun revoke() = invalidate(revoke = true)
    override fun close() = revoke()

    private fun invalidate(revoke: Boolean) {
        val prior: SSLSocket?
        synchronized(lock) {
            check(generation < Long.MAX_VALUE)
            generation++
            if (revoke) revoked = true
            prior = active
            active = null
        }
        prior?.let { runCatching { it.close() } }
    }
}
