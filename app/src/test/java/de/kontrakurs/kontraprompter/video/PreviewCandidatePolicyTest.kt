package de.kontrakurs.kontraprompter.video

import java.net.InetAddress
import org.junit.Assert.*
import org.junit.Test

class PreviewCandidatePolicyTest {
    private fun ip(value: String) = InetAddress.getByName(value)

    @Test fun `only private or link local addresses qualify`() {
        listOf("10.1.2.3", "172.16.4.3", "192.168.1.10", "169.254.1.1", "fd00::1", "fe80::1")
            .forEach { assertTrue(it, PreviewCandidatePolicy.privateHost(ip(it))) }
        listOf("127.0.0.1", "8.8.8.8", "172.32.0.1", "::1", "2001:4860:4860::8888")
            .forEach { assertFalse(it, PreviewCandidatePolicy.privateHost(ip(it))) }
    }

    @Test fun `candidate must match the actual subnet and address family`() {
        assertTrue(PreviewCandidatePolicy.sameSubnet(ip("192.168.2.71"), ip("192.168.2.7"), 24))
        assertFalse(PreviewCandidatePolicy.sameSubnet(ip("192.168.3.71"), ip("192.168.2.7"), 24))
        assertTrue(PreviewCandidatePolicy.sameSubnet(ip("10.1.1.8"), ip("10.1.1.9"), 31))
        assertFalse(PreviewCandidatePolicy.sameSubnet(ip("10.1.1.10"), ip("10.1.1.9"), 31))
        assertFalse(PreviewCandidatePolicy.sameSubnet(ip("10.1.1.8"), ip("10.1.1.9"), 0))
        assertFalse(PreviewCandidatePolicy.sameSubnet(ip("fd00::1"), ip("192.168.2.7"), 24))
    }

    @Test fun `SDP permits one video direction and no embedded targets`() {
        val offer = "v=0\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\nc=IN IP4 0.0.0.0\r\na=recvonly\r\n"
        assertTrue(validDescription(offer, "recvonly"))
        assertFalse(validDescription(offer + "a=candidate:1 1 udp 1 192.168.1.2 1234 typ host\r\n", "recvonly"))
        assertFalse(validDescription(offer.replace("0.0.0.0", "192.168.1.2"), "recvonly"))
        assertFalse(validDescription(offer + "m=audio 9 UDP/TLS/RTP/SAVPF 111\r\n", "recvonly"))
        assertFalse(validDescription(offer.replace("recvonly", "sendrecv"), "recvonly"))
    }
}
