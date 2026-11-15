package de.kontrakurs.kontraprompter.cloud

import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class StudioDeviceTranscriptTest {
    private val device = "2b8b26bc-d809-4f67-8759-028bb5891001"
    private val challenge = "2b8b26bc-d809-4f67-8759-028bb5891002"
    private val nonce = "A".repeat(43)
    private val key = Base64.getDecoder().decode("MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEBg5/9tat/9F9ijLKw7QxCXYs2mJAjMxaUi6xj6dXAXzaYz7Z450J6eJC32xlHXh6SowLTWsImMGMfEzTWcsDFw==")
    private val fingerprint = "504696c998dcfbd35069ad2c8da60b101b58e48fa280cd8332caf223f0612859"
    private val scope = listOf("recordings:upload", "projects:read")
    private val token = "$challenge.1234.$nonce.$nonce"

    @Test fun `enrollment transcript matches server UTF8 layout and sorted scope`() {
        val expected = "videomaker-studio-device-auth/enroll/v1\n$device\n$challenge\n$nonce\n$fingerprint\nprojects:read,recordings:upload\n${"a".repeat(64)}\n"
        assertEquals(expected, StudioDeviceTranscript.enrollment(device,challenge,nonce,key,scope,"a".repeat(64)).toString(Charsets.UTF_8))
    }

    @Test fun `login transcript matches server layout and rejects changed challenge`() {
        val expected = "videomaker-studio-device-auth/login/v1\n$device\n$token\n$fingerprint\nprojects:read,recordings:upload\n"
        assertEquals(expected, StudioDeviceTranscript.login(device,challenge,token,key,scope).toString(Charsets.UTF_8))
        assertThrows(IllegalArgumentException::class.java) { StudioDeviceTranscript.login(device,device,token,key,scope) }
    }

    @Test fun `malformed tokens, scopes, key material and transcript injection fail closed`() {
        for (bad in listOf("$challenge.0123.$nonce.$nonce", "$challenge.9007199254740992.$nonce.$nonce", "$challenge.1234.${"B".repeat(43)}.$nonce", token+"\n"))
            assertThrows(IllegalArgumentException::class.java) { StudioDeviceTranscript.login(device,challenge,bad,key,scope) }
        for (badScope in listOf(emptyList(), listOf("admin"), listOf("projects:read", "projects:read"), listOf("projects:read\nadmin")))
            assertThrows(IllegalArgumentException::class.java) { StudioDeviceTranscript.login(device,challenge,token,key,badScope) }
        assertThrows(IllegalArgumentException::class.java) { StudioDeviceTranscript.enrollment(device,challenge,nonce,byteArrayOf(),scope,"a".repeat(64)) }
        assertThrows(IllegalArgumentException::class.java) { StudioDeviceTranscript.enrollment(device,challenge,nonce,key,scope,"a".repeat(63)+"\n") }
    }
}
