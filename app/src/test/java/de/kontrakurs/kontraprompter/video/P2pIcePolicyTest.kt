package de.kontrakurs.kontraprompter.video

import java.net.InetAddress
import org.junit.Assert.*
import org.junit.Test

class P2pIcePolicyTest {
    private val local = InetAddress.getByName("192.0.2.1")
    private val peer = InetAddress.getByName("192.0.2.2")

    @Test fun `only known active interface bound to local address selects direct mode`() {
        for (name in listOf("p2p0", "p2p-wlan0-0")) assertTrue(p2pInterfaceMatches(name, true, false, local, listOf(local)))
        for (name in listOf("wlan0", "tun0", "p2p", "p2p0fake", "p2p-", "p2p-/bad")) assertFalse(p2pInterfaceMatches(name, true, false, local, listOf(local)))
        assertFalse(p2pInterfaceMatches("p2p0", false, false, local, listOf(local)))
        assertFalse(p2pInterfaceMatches("p2p0", true, true, local, listOf(local)))
        assertFalse(p2pInterfaceMatches("p2p0", true, false, local, listOf(peer)))
        assertFalse(p2pInterfaceMatches(null, true, false, local, listOf(local)))
    }

    @Test fun `only socket matched ipv4 host candidates pass`() {
        val candidate = "candidate:123 1 udp 123456 192.0.2.1 5000 typ host generation 0 network-id 1 network-cost 10"
        assertTrue(p2pCandidateMatches(candidate, local))
        assertFalse(p2pCandidateMatches(candidate, peer))
        assertFalse(p2pCandidateMatches(candidate.replace(" typ host", " typ relay"), local))
        assertFalse(p2pCandidateMatches(candidate.replace("192.0.2.1", "test.local"), local))
        assertFalse(p2pCandidateMatches(candidate.replace("192.0.2.1", "::1"), local))
        assertFalse(p2pCandidateMatches(candidate + " raddr 192.0.2.3", local))
        assertFalse(p2pCandidateMatches(candidate + " unknown x", local))
        assertFalse(p2pCandidateMatches(candidate.replace(" 1 udp ", " 2 udp "), local))
        assertFalse(p2pCandidateMatches(candidate.replace(" 5000 ", " 0 "), local))
        assertFalse(p2pCandidateMatches(candidate + "\n", local))
    }

    @Test fun `normal mode requires matching ordinary wifi address and interface`() {
        assertTrue(isMatchingOrdinaryWifi("wlan0", "wlan0", true, false, true, local, listOf(local)))
        assertFalse(isMatchingOrdinaryWifi("p2p0", "wlan0", true, false, true, local, listOf(local)))
        assertFalse(isMatchingOrdinaryWifi("wlan0", "wlan0", true, true, true, local, listOf(local)))
        assertFalse(isMatchingOrdinaryWifi("wlan0", "wlan0", true, false, false, local, listOf(local)))
        assertFalse(isMatchingOrdinaryWifi("wlan0", "wlan0", false, false, true, local, listOf(local)))
        assertFalse(isMatchingOrdinaryWifi("wlan0", "wlan0", true, false, true, local, listOf(peer)))
        assertFalse(isMatchingOrdinaryWifi(null, null, true, false, true, local, listOf(local)))
    }

    @Test fun `sdp cannot bypass candidate filtering or leak connection addresses`() {
        val fingerprint = List(32) { "AB" }.joinToString(":")
        val safe = "v=0\r\no=- 1 2 IN IP4 127.0.0.1\r\nc=IN IP4 0.0.0.0\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\na=rtcp:9 IN IP4 0.0.0.0\r\na=fingerprint:sha-256 $fingerprint\r\n"
        assertTrue(p2pDescriptionSafe(safe))
        assertFalse(p2pDescriptionSafe(safe + "a=candidate:1 1 udp 1 192.0.2.1 1 typ host\r\n"))
        assertFalse(p2pDescriptionSafe(safe + "a=remote-candidates:1 192.0.2.2 1\r\n"))
        assertFalse(p2pDescriptionSafe(safe.replace("c=IN IP4 0.0.0.0", "c=IN IP4 192.0.2.2")))
        assertFalse(p2pDescriptionSafe(safe.replace("127.0.0.1", "192.0.2.2")))
        assertFalse(p2pDescriptionSafe(safe.replace("a=rtcp:9 IN IP4 0.0.0.0", "a=rtcp:9 IN IP4 192.0.2.2")))
        assertFalse(p2pDescriptionSafe(safe.replace("UDP/TLS/RTP/SAVPF", "RTP/AVP")))
        assertFalse(p2pDescriptionSafe(safe.replace("a=fingerprint:sha-256 $fingerprint\r\n", "")))
        assertFalse(p2pDescriptionSafe(safe + " a=candidate:1 1 udp 1 192.0.2.1 1 typ host\r\n"))
    }
}
