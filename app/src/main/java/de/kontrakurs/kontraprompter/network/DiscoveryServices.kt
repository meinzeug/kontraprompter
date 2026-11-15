package de.kontrakurs.kontraprompter.network

/** Names change with each camera session; the suffix is random and is not a pairing secret. */
internal fun discoveryServiceName(model: String, nonce: ByteArray): String {
    require(nonce.size == 8)
    val safeModel = model.filter { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_' }
        .take(20).ifEmpty { "Kamera" }
    val suffix = nonce.joinToString("") { "%02x".format(it.toInt() and 255) }
    return "KontraPrompter-$safeModel-$suffix"
}

/** One latest selected discovery instance; handles are invalidated on replacement, loss or close. */
internal class DiscoveryServices<T : Any> {
    internal class Selection<T : Any> internal constructor(internal val item: T)
    private val services = LinkedHashMap<String, T>()
    private var selected: Selection<T>? = null
    private var open = true

    @Synchronized fun found(name: String, item: T): List<T> {
        if (!open) return emptyList()
        val old = services.put(name, item)
        if (old != null && selected?.item === old) selected = null
        return services.values.toList()
    }
    @Synchronized fun lost(name: String): List<T> {
        if (!open) return emptyList()
        val old = services.remove(name)
        if (old != null && selected?.item === old) selected = null
        return services.values.toList()
    }
    @Synchronized fun select(item: T): Selection<T>? {
        if (!open || services.values.none { it === item }) return null
        return Selection(item).also { selected = it }
    }
    @Synchronized fun current(selection: Selection<T>): Boolean =
        open && selected === selection && services.values.any { it === selection.item }
    @Synchronized fun clearSelection() { selected = null }
    @Synchronized fun close() { open = false; selected = null; services.clear() }
}
