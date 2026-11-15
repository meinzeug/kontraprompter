package de.kontrakurs.kontraprompter.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

private const val SERVICE_TYPE = "_kontraprompter._tcp."
private const val TOKEN_TTL = 5 * 60 * 1000L

data class FoundDevice(val name: String, val info: NsdServiceInfo)
data class LanState(
    val role: String = "", val status: String = "Nicht verbunden", val error: String? = null,
    val token: String = "", val addresses: List<String> = emptyList(), val port: Int = 0,
    val devices: List<FoundDevice> = emptyList(), val remoteName: String = "",
    val authenticated: Boolean = false, val localApproved: Boolean = false,
    val remoteApproved: Boolean = false, val ready: Boolean = false, val rttMs: Long? = null,
)

/** A late first frame must not overwrite a newer connection result or an error. */
internal fun firstCameraFrame(state: LanState): LanState =
    if (state.ready && state.error == null && state.status == "Gekoppelt; Video wird vorbereitet")
        state.copy(status = "Kamerabild verfügbar; Verbindung wird aufgebaut")
    else state

/** A single short-lived LAN session. All SDP and ICE messages pass through the authenticated channel. */
class LanSession(context: Context) : AutoCloseable {
    private val app = context.applicationContext
    private val nsd = app.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(LanState())
    val state: StateFlow<LanState> = _state
    private val closed = AtomicBoolean(false)
    private val readyDelivered = AtomicBoolean(false)
    private val connected = AtomicBoolean(false)
    private val socketLock = Any()
    // Serializes application callbacks with installation of a subsequent channel.
    // Never acquire it while holding socketLock; callbacks can acquire other locks.
    private val deliveryLock = Any()
    private var disconnecting = false
    private var server: ServerSocket? = null
    private var pendingSocket: Socket? = null
    @Volatile private var channel: AuthenticatedChannel? = null
    private class PendingApproval(val connection: AuthenticatedChannel) { val done = CountDownLatch(1) }
    private var pendingApproval: PendingApproval? = null
    private var registration: NsdManager.RegistrationListener? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    @Volatile var onReady: (() -> Unit)? = null
    @Volatile var onMessage: ((Any, String, String) -> Unit)? = null
    @Volatile var onDisconnect: (() -> Unit)? = null
    private val deviceName = (Build.MANUFACTURER + " " + Build.MODEL).take(24)

    fun startHost() {
        if (_state.value.role.isNotEmpty()) return
        _state.value = LanState(role = "host", status = "Starte lokalen Dienst …", token = PairingCrypto.token())
        val createdAt = android.os.SystemClock.elapsedRealtime()
        scope.launch {
            try {
                val listener = ServerSocket(0).also { it.soTimeout = 1000 }
                synchronized(socketLock) {
                    if (closed.get()) { listener.close(); return@launch }
                    server = listener
                }
                _state.update { it.copy(status = "Warte auf Anzeigegerät", port = listener.localPort, addresses = localAddresses()) }
                register(listener.localPort)
                var authenticatedOnce = false
                while (!closed.get() && channel == null && android.os.SystemClock.elapsedRealtime() - createdAt < TOKEN_TTL) {
                    val socket = try { listener.accept() } catch (_: SocketTimeoutException) { continue }
                    if (!trackPending(socket)) break
                    socket.soTimeout = 15_000
                    try {
                        val accepted = AuthenticatedChannel.host(socket, _state.value.token, deviceName)
                        if (android.os.SystemClock.elapsedRealtime() - createdAt >= TOKEN_TTL) { clearPending(socket); accepted.close(); break }
                        if (!install(accepted, socket)) break
                        authenticatedOnce = true
                        listener.close(); server = null; unregister()
                        authenticated(accepted)
                        break
                    } catch (_: Exception) {
                        clearPending(socket)
                        runCatching { socket.close() }
                        _state.update { it.copy(error = "Kopplungsversuch abgelehnt. Sitzungscode und Netzwerk prüfen.") }
                    }
                }
                if (!closed.get() && !authenticatedOnce && channel == null) { unregister(); runCatching { listener.close() }; _state.update { it.copy(status = "Sitzungscode abgelaufen", token = "", error = "Neue Sitzung starten.") } }
            } catch (e: Exception) { if (!closed.get()) fail("Lokaler Dienst konnte nicht gestartet werden: ${e.localizedMessage}") }
        }
    }
    fun startDiscovery() {
        if (_state.value.role.isNotEmpty()) return
        _state.value = LanState(role = "client", status = "Suche Kamerageräte im lokalen Netz …")
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) = Unit
            override fun onDiscoveryStopped(type: String) = Unit
            override fun onStartDiscoveryFailed(type: String, code: Int) { _state.update { it.copy(error = "Suche fehlgeschlagen ($code); manuelle Adresse verwenden.") } }
            override fun onStopDiscoveryFailed(type: String, code: Int) = Unit
            override fun onServiceFound(info: NsdServiceInfo) { if (info.serviceType.trimEnd('.') == SERVICE_TYPE.trimEnd('.')) _state.update { s -> s.copy(devices = (s.devices.filterNot { it.name == info.serviceName } + FoundDevice(info.serviceName, info))) } }
            override fun onServiceLost(info: NsdServiceInfo) { _state.update { s -> s.copy(devices = s.devices.filterNot { it.name == info.serviceName }) } }
        }
        discovery = listener
        try { nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) } catch (_: Exception) { _state.update { it.copy(error = "Suche nicht verfügbar; manuelle Adresse verwenden.") } }
    }
    @Suppress("DEPRECATION")
    fun resolve(device: FoundDevice, result: (String, Int) -> Unit) {
        try {
            nsd.resolveService(device.info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, code: Int) { _state.update { it.copy(error = "Gerät nicht auflösbar ($code); manuelle Adresse verwenden.") } }
                override fun onServiceResolved(info: NsdServiceInfo) { info.host?.hostAddress?.let { result(it, info.port) } }
            })
        } catch (_: Exception) { _state.update { it.copy(error = "Gerät nicht auflösbar; manuelle Adresse verwenden.") } }
    }
    fun connect(address: String, port: Int, token: String) {
        if (channel != null || _state.value.authenticated) return
        try { PairingCrypto.parseToken(token); require(port in 1..65535); require(isNumericIp(address.trim())) { "Bitte eine numerische IP-Adresse eingeben." } } catch (e: Exception) { _state.update { it.copy(error = e.localizedMessage ?: "Adresse, Port oder Sitzungscode ungültig") }; return }
        readyDelivered.set(false)
        _state.update { it.copy(status = "Verbinde …", error = null) }
        scope.launch {
            val socket = Socket()
            if (!trackPending(socket)) return@launch
            try {
                socket.connect(InetSocketAddress(address.trim(), port), 6000); socket.soTimeout = 15_000
                val connection = AuthenticatedChannel.client(socket, token, deviceName)
                if (install(connection, socket)) authenticated(connection)
            } catch (_: Exception) { clearPending(socket); runCatching { socket.close() }; if (!closed.get()) _state.update { it.copy(status = "Nicht verbunden", error = "Verbindung fehlgeschlagen. Adresse, Port, Sitzungscode und gemeinsames Netz prüfen.") } }
        }
    }
    private fun authenticated(connection: AuthenticatedChannel) {
        synchronized(socketLock) {
            if (closed.get() || !connected.get() || channel !== connection) return
            _state.update { it.copy(status = "Gegenseitige Bestätigung erforderlich", error = null, token = "", authenticated = true, remoteName = connection.remoteName) }
        }
        scope.launch {
            try { while (isActive && !closed.get() && channel === connection) { delay(5000); connection.send("ping", android.os.SystemClock.elapsedRealtime().toString()) } } catch (_: Exception) { disconnect("Verbindung unterbrochen", connection) }
        }
        try {
            while (!closed.get()) {
                val (type, payload) = connection.receive()
                when (type) {
                    "ping" -> connection.send("pong", payload)
                    "pong" -> payload.toLongOrNull()?.let { sent ->
                        val elapsed = android.os.SystemClock.elapsedRealtime() - sent
                        if (elapsed in 0..60_000) _state.update { it.copy(rttMs = elapsed) }
                    }
                    "approve" -> applyApproval(connection, local = false)
                    else -> deliverApprovedMessage(connection, type, payload)
                }
            }
        } catch (_: Exception) { if (!closed.get()) disconnect("Verbindung verloren. Bitte erneut koppeln.", connection) }
    }
    fun approve() {
        val pending = synchronized(socketLock) {
            val current = channel ?: return
            if (closed.get() || !connected.get() || !_state.value.authenticated || _state.value.localApproved || pendingApproval != null) return
            PendingApproval(current).also { pendingApproval = it }
        }
        val connection = pending.connection
        val job = scope.launch {
            // Socket I/O must not hold the lifecycle lock: a blocked send must
            // never prevent disconnect from invalidating the connection.
            try { connection.send("approve", ""); applyApproval(connection, local = true) }
            catch (_: Exception) { disconnect("Bestätigung konnte nicht gesendet werden.", connection) }
            finally { finishApproval(pending) }
        }
        // Also release the receiver if the coroutine was cancelled before starting.
        job.invokeOnCompletion { finishApproval(pending) }
    }
    private fun finishApproval(pending: PendingApproval) {
        synchronized(socketLock) { if (pendingApproval === pending) pendingApproval = null }
        pending.done.countDown()
    }

    /** The peer may send SDP immediately after reading our still-flushing approval. */
    private fun deliverApprovedMessage(connection: AuthenticatedChannel, type: String, payload: String) {
        val pending = synchronized(socketLock) {
            if (closed.get() || !connected.get() || channel !== connection) throw IllegalStateException("Verbindung beendet")
            pendingApproval?.takeIf { it.connection === connection }
        }
        // Never hold socketLock while waiting: disconnect/close must wake this wait.
        if (pending != null && !pending.done.await(15, TimeUnit.SECONDS)) throw IllegalStateException("Bestätigung dauert zu lange")
        synchronized(deliveryLock) {
            if (!isApprovedConnection(connection)) throw IllegalStateException("Nachricht vor Bestätigung")
            onMessage?.invoke(connection, type, payload)
        }
    }
    /** Approval results are bound to the exact channel, including after a blocked send. */
    private fun applyApproval(connection: AuthenticatedChannel, local: Boolean) {
        synchronized(deliveryLock) {
            val becameReady = synchronized(socketLock) {
                if (closed.get() || !connected.get() || channel !== connection || !_state.value.authenticated) return
                _state.update { if (local) it.copy(localApproved = true) else it.copy(remoteApproved = true) }
                val s = _state.value
                if (s.localApproved && s.remoteApproved && readyDelivered.compareAndSet(false, true)) {
                    _state.update { it.copy(ready = true, status = "Gekoppelt; Video wird vorbereitet") }
                    true
                } else false
            }
            // The callback stays outside socketLock, but must complete before a
            // different channel can be installed or receive application messages.
            if (becameReady && isApprovedConnection(connection)) onReady?.invoke()
        }
    }
    fun send(type: String, payload: String): Boolean {
        val current = channel ?: return false
        if (!_state.value.ready || closed.get()) return false
        scope.launch { try { current.send(type, payload) } catch (_: Exception) { disconnect("Verbindung verloren", current) } }
        return true
    }
    /** Never let a delayed command migrate to a replacement connection. */
    internal fun sendOnApprovedConnection(marker: Any, type: String, payload: String): Boolean = synchronized(socketLock) {
        val current = channel ?: return false
        if (closed.get() || !connected.get() || !_state.value.ready || current !== marker) return false
        scope.launch { try { current.send(type, payload) } catch (_: Exception) { disconnect("Verbindung verloren", current) } }
        true
    }
    /** An opaque identity for this exact approved connection, invalidated on disconnect or close. */
    internal fun approvedConnectionMarker(): Any? = synchronized(socketLock) {
        if (!closed.get() && connected.get() && _state.value.ready) channel else null
    }
    internal fun isApprovedConnection(marker: Any): Boolean = synchronized(socketLock) {
        !closed.get() && connected.get() && _state.value.ready && channel === marker
    }
    /** The callback must only perform quick in-memory admission, never I/O. */
    internal fun <T> admitOnApprovedConnection(marker: Any, admit: () -> T): T? = synchronized(socketLock) {
        if (closed.get() || !connected.get() || !_state.value.ready || channel !== marker) null else admit()
    }
    private fun disconnect(message: String, expected: AuthenticatedChannel) {
        synchronized(socketLock) {
            if (closed.get() || channel !== expected || !connected.compareAndSet(true, false)) return
            disconnecting = true
            channel = null
            pendingApproval?.done?.countDown(); pendingApproval = null
            _state.update { it.copy(status = "Nicht verbunden", error = message, authenticated = false, ready = false, localApproved = false, remoteApproved = false, rttMs = null) }
        }
        runCatching { expected.close() }
        synchronized(deliveryLock) {
            try { if (!closed.get()) onDisconnect?.invoke() }
            finally { synchronized(socketLock) { disconnecting = false } }
        }
    }
    fun report(status: String, error: String? = null) { _state.update { it.copy(status = status, error = error) } }
    fun reportFirstCameraFrame() { _state.update(::firstCameraFrame) }
    private fun fail(message: String) { _state.update { it.copy(status = "Fehler", error = message) } }
    private fun register(port: Int) {
        val info = NsdServiceInfo().apply { serviceName = "KontraPrompter-${Build.MODEL.take(24)}"; serviceType = SERVICE_TYPE; setPort(port) }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) = Unit
            override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) { _state.update { it.copy(error = "Automatische Suche nicht verfügbar ($code); Adresse manuell eingeben.") } }
            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) = Unit
        }
        synchronized(socketLock) {
            if (closed.get()) return
            registration = listener
            try { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) } catch (_: Exception) { _state.update { it.copy(error = "Automatische Suche nicht verfügbar; Adresse manuell eingeben.") } }
        }
    }
    private fun trackPending(socket: Socket): Boolean = synchronized(socketLock) {
        if (closed.get() || pendingSocket != null || channel != null) { runCatching { socket.close() }; false }
        else { pendingSocket = socket; true }
    }
    private fun clearPending(socket: Socket) = synchronized(socketLock) { if (pendingSocket === socket) pendingSocket = null }
    private fun install(connection: AuthenticatedChannel, socket: Socket): Boolean {
        val installed = synchronized(deliveryLock) { synchronized(socketLock) {
            if (closed.get() || disconnecting || channel != null || pendingSocket !== socket) {
                if (pendingSocket === socket) pendingSocket = null
                if (!closed.get() && disconnecting) _state.update { it.copy(status = "Nicht verbunden", error = "Vorherige Verbindung wird noch beendet. Bitte erneut koppeln.") }
                false
            } else { pendingSocket = null; channel = connection; connected.set(true); true }
        } }
        if (!installed) runCatching { connection.close() }
        return installed
    }
    private fun isNumericIp(value: String): Boolean {
        val address = value.substringBefore('%')
        val zone = value.substringAfter('%', "")
        if (zone.isNotEmpty() && !zone.matches(Regex("[A-Za-z0-9_.-]+"))) return false
        if (address.contains(':')) return address.matches(Regex("[0-9a-fA-F:]+"))
        val parts = address.split('.')
        return zone.isEmpty() && parts.size == 4 && parts.all { it.isNotEmpty() && it.length <= 3 && it.all(Char::isDigit) && (it.toIntOrNull() ?: 256) <= 255 }
    }
    private fun unregister() { registration?.let { runCatching { nsd.unregisterService(it) } }; registration = null }
    private fun localAddresses(): List<String> = runCatching { NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }.flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>().filterNot { it.isLoopbackAddress || it.isLinkLocalAddress }.mapNotNull { it.hostAddress } }.getOrDefault(emptyList())
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(socketLock) {
            pendingApproval?.done?.countDown(); pendingApproval = null
            runCatching { pendingSocket?.close() }; pendingSocket = null
            runCatching { channel?.close() }; channel = null
            runCatching { server?.close() }; server = null
        }
        unregister()
        discovery?.let { runCatching { nsd.stopServiceDiscovery(it) } }; discovery = null
        scope.cancel()
    }
}
