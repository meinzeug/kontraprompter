package de.kontrakurs.kontraprompter.video

import java.net.Inet4Address
import java.net.InetAddress

/** P2P names are an explicit device heuristic, not an Android group attestation. */
internal fun p2pInterfaceMatches(name: String?, up: Boolean, loopback: Boolean, local: InetAddress, addresses: Collection<InetAddress>): Boolean =
    up && !loopback && name != null && (name == "p2p0" || name.matches(Regex("p2p-[A-Za-z0-9_.-]{1,48}"))) &&
        local is Inet4Address && !local.isAnyLocalAddress && !local.isLoopbackAddress && !local.isMulticastAddress && local in addresses

internal fun isProvenDefaultWifi(interfaceName: String?, defaultInterface: String?, wifi: Boolean, vpn: Boolean, internet: Boolean, local: InetAddress, defaultAddresses: Collection<InetAddress>): Boolean =
    interfaceName != null && interfaceName == defaultInterface && wifi && !vpn && internet && local in defaultAddresses

/** Only literal IPv4 host candidates matching the authenticated socket peer. */
internal fun p2pCandidateMatches(candidate: String, expected: InetAddress): Boolean {
    if (expected !is Inet4Address || candidate.length !in 1..1024 || candidate.any { it.code < 32 || it.code == 127 }) return false
    val fields = candidate.trim().split(Regex("\\s+"))
    if (fields.size < 8 || (fields.size - 8) % 2 != 0 || !fields[0].matches(Regex("candidate:[A-Za-z0-9+/]{1,64}")) ||
        fields[1] != "1" || fields[2].lowercase() !in setOf("udp", "tcp") ||
        (fields[3].toLongOrNull() ?: return false) !in 0L..4294967295L ||
        fields[4] != expected.hostAddress || (fields[5].toIntOrNull() ?: return false) !in 1..65535 ||
        !fields[6].equals("typ", true) || !fields[7].equals("host", true)) return false
    return fields.drop(8).chunked(2).all { (key, value) ->
        key.lowercase() in setOf("generation", "network-id", "network-cost", "ufrag", "tcptype") &&
            value.matches(Regex("[A-Za-z0-9+/_-]{1,256}"))
    }
}

/** Reject SDP escape hatches rather than modifying DTLS parameters or candidates. */
internal fun p2pDescriptionSafe(sdp: String): Boolean {
    if (sdp.toByteArray(Charsets.UTF_8).size !in 1..65_536 || sdp.any { it.code < 32 && it != '\r' && it != '\n' && it != '\t' }) return false
    val lines = sdp.split(Regex("\\r?\\n"))
    if (lines.any { it.startsWith(" ") || it.startsWith("\t") }) return false
    if (!sdp.startsWith("v=0") || lines.any { it.startsWith("a=candidate:", true) || it.startsWith("a=remote-candidates:", true) }) return false
    val media = lines.filter { it.startsWith("m=", true) }
    if (media.size != 1 || !media.single().matches(Regex("m=video [1-9][0-9]{0,4} UDP/TLS/RTP/SAVPF(?: [0-9]+)+", RegexOption.IGNORE_CASE))) return false
    val fingerprints = lines.filter { it.startsWith("a=fingerprint:", true) }
    if (fingerprints.isEmpty() || fingerprints.any { !it.matches(Regex("a=fingerprint:sha-256 (?:[0-9A-Fa-f]{2}:){31}[0-9A-Fa-f]{2}", RegexOption.IGNORE_CASE)) }) return false
    if (lines.any { it.startsWith("c=", true) && it != "c=IN IP4 0.0.0.0" && it != "c=IN IP6 ::" }) return false
    if (lines.any { it.startsWith("a=rtcp:", true) && !it.matches(Regex("a=rtcp:[0-9]+(?: IN IP4 0\\.0\\.0\\.0)?", RegexOption.IGNORE_CASE)) }) return false
    val origin = lines.filter { it.startsWith("o=", true) }
    return origin.size == 1 && (origin.single().endsWith(" IN IP4 127.0.0.1") || origin.single().endsWith(" IN IP4 0.0.0.0") || origin.single().endsWith(" IN IP6 ::1") || origin.single().endsWith(" IN IP6 ::"))
}
