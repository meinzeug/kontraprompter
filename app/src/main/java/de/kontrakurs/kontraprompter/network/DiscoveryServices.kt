package de.kontrakurs.kontraprompter.network

import java.net.Socket

/** Names change with each camera session; the suffix is random and is not a pairing secret. */
internal fun discoveryServiceName(model: String, nonce: ByteArray): String {
    require(nonce.size == 8)
    val safeModel = model.filter { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_' }
        .take(20).ifEmpty { "Kamera" }
    val suffix = nonce.joinToString("") { "%02x".format(it.toInt() and 255) }
    return "KontraPrompter-$safeModel-$suffix"
}

/** One selected discovery instance, optionally owning a socket until authenticated installation. */
internal class DiscoveryServices<T : Any> {
    internal class Selection<T : Any> internal constructor(internal val item: T) {
        // Accessed only while holding the enclosing DiscoveryServices monitor.
        internal var socket: Socket? = null
    }
    private val services = LinkedHashMap<String, T>()
    private var selected: Selection<T>? = null
    private var open = true

    private fun detachSelected(): Socket? {
        val old = selected
        selected = null
        return old?.socket.also { old?.socket = null }
    }
    private fun close(socket: Socket?) { socket?.let { runCatching { it.close() } } }

    fun found(name: String, item: T): List<T> {
        var cancel: Socket? = null
        val snapshot = synchronized(this) {
            if (!open) return@synchronized emptyList()
            val old = services.put(name, item)
            if (old != null && selected?.item === old) cancel = detachSelected()
            services.values.toList()
        }
        close(cancel)
        return snapshot
    }
    fun lost(name: String): List<T> {
        var cancel: Socket? = null
        val snapshot = synchronized(this) {
            if (!open) return@synchronized emptyList()
            val old = services.remove(name)
            if (old != null && selected?.item === old) cancel = detachSelected()
            services.values.toList()
        }
        close(cancel)
        return snapshot
    }
    fun select(item: T): Selection<T>? {
        var cancel: Socket? = null
        val selection = synchronized(this) {
            if (!open || services.values.none { it === item }) return@synchronized null
            cancel = detachSelected()
            Selection(item).also { selected = it }
        }
        close(cancel)
        return selection
    }
    @Synchronized fun current(selection: Selection<T>): Boolean =
        open && selected === selection && services.values.any { it === selection.item }
    @Synchronized fun hasSelection(): Boolean = open && selected != null

    /** Keep work under this monitor in-memory or strictly nonblocking. */
    @Synchronized fun <R> ifCurrent(selection: Selection<T>, action: () -> R): R? =
        if (current(selection)) action() else null

    @Synchronized fun attach(selection: Selection<T>, socket: Socket): Boolean {
        if (!current(selection) || selection.socket != null) return false
        selection.socket = socket
        return true
    }
    @Synchronized fun detach(selection: Selection<T>, socket: Socket) {
        if (selection.socket === socket) selection.socket = null
    }
    fun clearSelection() {
        val cancel = synchronized(this) { detachSelected() }
        close(cancel)
    }
    fun close() {
        val cancel = synchronized(this) { open = false; services.clear(); detachSelected() }
        close(cancel)
    }
}
