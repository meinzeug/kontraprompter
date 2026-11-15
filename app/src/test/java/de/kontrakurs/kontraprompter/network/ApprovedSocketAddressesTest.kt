package de.kontrakurs.kontraprompter.network

import de.kontrakurs.kontraprompter.test.jvmAllocate
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

class ApprovedSocketAddressesTest {
    private fun set(target: Any, name: String, value: Any?) {
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
    }
    private fun channel(local: InetAddress, remote: InetAddress): AuthenticatedChannel {
        val socket = object : Socket() {
            override fun getLocalAddress(): InetAddress = local
            override fun getInetAddress(): InetAddress = remote
        }
        val ctor = AuthenticatedChannel::class.java.declaredConstructors.single { it.parameterCount == 8 }.apply { isAccessible = true }
        return ctor.newInstance(socket, DataInputStream(ByteArrayInputStream(byteArrayOf())), DataOutputStream(ByteArrayOutputStream()),
            ByteArray(16), ByteArray(32), ByteArray(32), "client", "fixture") as AuthenticatedChannel
    }
    @Test fun `snapshot belongs only to the current approved channel`() {
        val lan = jvmAllocate(LanSession::class.java)
        val first = channel(InetAddress.getByName("192.0.2.1"), InetAddress.getByName("192.0.2.2"))
        val replacement = channel(InetAddress.getByName("192.0.2.3"), InetAddress.getByName("192.0.2.4"))
        set(lan, "socketLock", Any()); set(lan, "closed", AtomicBoolean(false)); set(lan, "connected", AtomicBoolean(true))
        set(lan, "_state", MutableStateFlow(LanState(ready = true))); set(lan, "channel", first)
        val captured = checkNotNull(lan.approvedSocketAddresses())
        assertSame(first, captured.marker)
        assertTrue(lan.isApprovedConnection(captured.marker))
        set(lan, "channel", replacement)
        assertFalse(lan.isApprovedConnection(captured.marker))
        assertSame(replacement, checkNotNull(lan.approvedSocketAddresses()).marker)
        set(lan, "connected", AtomicBoolean(false))
        assertNull(lan.approvedSocketAddresses())
    }
}
