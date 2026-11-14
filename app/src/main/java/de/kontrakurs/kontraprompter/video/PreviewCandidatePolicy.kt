package de.kontrakurs.kontraprompter.video

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** The preview may contact only a host on the current Wi-Fi or Ethernet subnet. */
internal object PreviewCandidatePolicy {
    internal data class NetworkSnapshot(val network: Network, val links: Set<String>)
    private val resolver = ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, ArrayBlockingQueue(1), { task ->
        Thread(task, "preview-mdns").apply { isDaemon = true }
    }, ThreadPoolExecutor.AbortPolicy())
    private val mdns = Regex("^[a-z0-9-]{1,63}\\.local$", RegexOption.IGNORE_CASE)

    fun snapshot(context: Context): NetworkSnapshot? {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val network = manager.activeNetwork ?: return null
        val capabilities = manager.getNetworkCapabilities(network) ?: return null
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
            !(capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) return null
        val links = manager.getLinkProperties(network)?.linkAddresses?.map { "${it.address.hostAddress}/${it.prefixLength}" }?.toSet().orEmpty()
        if (links.isEmpty() || manager.activeNetwork != network) return null
        return NetworkSnapshot(network, links)
    }

    fun checked(context: Context, candidate: String, expected: NetworkSnapshot): String? {
        if (snapshot(context) != expected) return null
        if (candidate.toByteArray(Charsets.UTF_8).size > 1024 || candidate.any { it.code < 32 || it.code == 127 }) return null
        val parts = candidate.trim().split(Regex("\\s+"))
        if (parts.size < 8 || !parts[0].matches(Regex("candidate:[a-z0-9+/]{1,64}", RegexOption.IGNORE_CASE)) ||
            parts[1] != "1" || parts[2].lowercase() !in setOf("udp", "tcp") || (parts[3].toLongOrNull() ?: return null) !in 0L..4294967295L ||
            (parts[5].toIntOrNull() ?: return null) !in 1..65535 || !parts[6].equals("typ", true) || !parts[7].equals("host", true) ||
            parts.drop(8).any { it.equals("raddr", true) || it.equals("rport", true) }) return null
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val network = expected.network
        val links = manager.getLinkProperties(network)?.linkAddresses ?: return null
        val host = parts[4]
        val addresses = try {
            when {
                mdns.matches(host) -> {
                    val future = resolver.submit<Array<InetAddress>> { network.getAllByName(host) }
                    try { future.get(2, TimeUnit.SECONDS).toList() } finally { future.cancel(true) }
                }
                host.matches(Regex("[0-9.]+")) || host.matches(Regex("[0-9a-fA-F:]+")) -> listOf(InetAddress.getByName(host))
                else -> return null
            }
        } catch (_: Exception) { return null }
        // Reject an ambiguous name rather than letting WebRTC select an unchecked address.
        if (addresses.size != 1) return null
        val address = addresses.single()
        if (!privateHost(address) || links.none { local -> sameSubnet(address, local.address, local.prefixLength) }) return null
        // A network change during resolution invalidates the proof.
        if (snapshot(context) != expected) return null
        return parts.toMutableList().apply { this[4] = address.hostAddress?.substringBefore('%') ?: return null }.joinToString(" ")
    }

    internal fun privateHost(address: InetAddress): Boolean {
        val b = address.address.map { it.toInt() and 255 }
        return when (address) {
            is Inet4Address -> b[0] == 10 || (b[0] == 172 && b[1] in 16..31) ||
                (b[0] == 192 && b[1] == 168) || (b[0] == 169 && b[1] == 254)
            is Inet6Address -> (b[0] and 0xfe) == 0xfc || (b[0] == 0xfe && (b[1] and 0xc0) == 0x80)
            else -> false
        }
    }

    internal fun sameSubnet(candidate: InetAddress, local: InetAddress, prefix: Int): Boolean {
        val a = candidate.address; val b = local.address
        if (a.size != b.size || prefix !in 1..a.size * 8 || !privateHost(local)) return false
        for (i in a.indices) {
            val bits = (prefix - i * 8).coerceIn(0, 8)
            val mask = if (bits == 0) 0 else (0xff shl (8 - bits)) and 0xff
            if (((a[i].toInt() xor b[i].toInt()) and mask) != 0) return false
        }
        return true
    }
}
