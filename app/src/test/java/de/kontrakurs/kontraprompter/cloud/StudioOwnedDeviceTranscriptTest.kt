package de.kontrakurs.kontraprompter.cloud

import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class StudioOwnedDeviceTranscriptTest {
    private val device = "2b8b26bc-d809-4f67-8759-028bb5891001"
    private val challenge = "2b8b26bc-d809-4f67-8759-028bb5891002"
    private val nonce = "A".repeat(43)
    private val key = Base64.getDecoder().decode("MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEBg5/9tat/9F9ijLKw7QxCXYs2mJAjMxaUi6xj6dXAXzaYz7Z450J6eJC32xlHXh6SowLTWsImMGMfEzTWcsDFw==")
    private val fingerprint = "504696c998dcfbd35069ad2c8da60b101b58e48fa280cd8332caf223f0612859"
    private val scope = listOf("recordings:upload", "projects:read")
    private val token = "$challenge.1234.$nonce.$nonce"
    private val owner = "a".repeat(64)
    private val workspace = "b".repeat(64)
    private val session = "c".repeat(64)

    @Test fun enrollmentBindsOwnerWorkspaceAndConfirmingSession() {
        val expected = "videomaker-studio-device-auth/enroll/v2\n$device\n$challenge\n$nonce\n$fingerprint\nprojects:read,recordings:upload\n$owner\n$workspace\n$session\n"
        assertEquals(expected, StudioOwnedDeviceTranscript.enrollment(device,challenge,nonce,key,scope,owner,workspace,session).toString(Charsets.UTF_8))
        assertThrows(IllegalArgumentException::class.java) { StudioOwnedDeviceTranscript.enrollment(device,challenge,nonce,key,scope,owner,workspace,"$session\n") }
    }

    @Test fun loginBindsOwnerAndWorkspace() {
        val expected = "videomaker-studio-device-auth/login/v2\n$device\n$token\n$fingerprint\nprojects:read,recordings:upload\n$owner\n$workspace\n"
        assertEquals(expected, StudioOwnedDeviceTranscript.login(device,challenge,token,key,scope,owner,workspace).toString(Charsets.UTF_8))
        for (bad in listOf("A".repeat(64), "0".repeat(63), "b".repeat(63)+"\n")) {
            assertThrows(IllegalArgumentException::class.java) { StudioOwnedDeviceTranscript.login(device,challenge,token,key,scope,bad,workspace) }
            assertThrows(IllegalArgumentException::class.java) { StudioOwnedDeviceTranscript.login(device,challenge,token,key,scope,owner,bad) }
        }
        assertThrows(IllegalArgumentException::class.java) { StudioOwnedDeviceTranscript.login(device,device,token,key,scope,owner,workspace) }
    }
}
