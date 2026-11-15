package de.kontrakurs.kontraprompter

/** QR contains a bearer secret. Never log it, store it, or include it in errors. */
internal data class QrPairing(val address: String, val port: Int, val token: String, val expiresAt: Long) {
    override fun toString() = "QrPairing(address=$address, port=$port, token=[redacted], expiresAt=$expiresAt)"
}

/** One successful decode per scanner lifecycle, even if camera frames arrive together. */
internal class QrScanGate {
    private val delivered = java.util.concurrent.atomic.AtomicBoolean(false)
    fun submit(value: String, accept: (String) -> Boolean) {
        if (!delivered.compareAndSet(false, true)) return
        if (!accept(value)) delivered.set(false)
    }
}

internal object PairingQr {
    private fun ipv4(value: String): Boolean {
        val parts = value.split('.')
        return parts.size == 4 && parts.all { it.matches(Regex("0|[1-9][0-9]{0,2}")) && (it.toIntOrNull() ?: 256) <= 255 } &&
            parts[0] != "0" && parts[0] != "127" && parts[0].toInt() < 224 && !(parts[0] == "169" && parts[1] == "254")
    }
    fun encode(address: String, port: Int, token: String, now: Long): String? {
        if (!ipv4(address) || port !in 1..65535 || !token.matches(Regex("[0-9a-f]{32}"))) return null
        return "kp1|$address|$port|$token|${now + 60_000L}"
    }
    fun decode(value: String, now: Long): QrPairing? {
        if (value.length > 160) return null
        val parts = value.split('|')
        if (parts.size != 5 || parts[0] != "kp1" || !ipv4(parts[1]) || !parts[2].matches(Regex("[1-9][0-9]{0,4}")) || !parts[3].matches(Regex("[0-9a-f]{32}"))) return null
        val port = parts[2].toIntOrNull() ?: return null
        val expires = parts[4].toLongOrNull() ?: return null
        if (port !in 1..65535 || expires <= now || expires > now + 90_000L) return null
        return QrPairing(parts[1], port, parts[3], expires)
    }
}
