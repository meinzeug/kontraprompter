package de.kontrakurs.kontraprompter.cloud

import de.kontrakurs.kontraprompter.video.RecordingOrigin
import java.net.HttpURLConnection
import java.net.URL
import org.junit.Assert.*
import org.junit.Test

class LanUploadIdentityTest {
    private val recording="00000000-0000-4000-8000-000000000004"
    private val project="00000000-0000-4000-8000-000000000003"
    private val other="00000000-0000-4000-8000-000000000005"
    private val upload=uploadIdFor(recording,project)
    private fun connection() = object : HttpURLConnection(URL("https://fixture.invalid/")) {
        override fun connect() { error("no network") }
        override fun disconnect() = Unit
        override fun usingProxy() = false
    }

    @Test fun `LAN and unassigned LAN send the stable original id on retries`() {
        for ((origin,bound) in listOf(RecordingOrigin.LAN to project,RecordingOrigin.LAN_UNASSIGNED to null)) {
            val first=lanUploadRecordingId(origin,recording,bound,project,upload)
            val retry=lanUploadRecordingId(origin,recording,bound,project,upload)
            assertEquals(recording,first);assertEquals(first,retry)
            val fake=connection();applyLanUploadIdentity(fake,first)
            assertEquals(recording,fake.getRequestProperty("X-Lan-Recording-Id"))
            assertNull(fake.getRequestProperty("X-Camera-Recording-Id"))
        }
    }

    @Test fun `manual and WEB never add LAN header`() {
        for ((origin,bound) in listOf(RecordingOrigin.MANUAL to null,RecordingOrigin.WEB to project)) {
            val fake=connection();applyLanUploadIdentity(fake,lanUploadRecordingId(origin,recording,bound,project,upload))
            assertNull(fake.getRequestProperty("X-Lan-Recording-Id"))
        }
    }

    @Test fun `mismatched journal identity or project fails before a header is made`() {
        assertThrows(IllegalArgumentException::class.java) { lanUploadRecordingId(RecordingOrigin.LAN,recording,project,other,uploadIdFor(recording,other)) }
        assertThrows(IllegalArgumentException::class.java) { lanUploadRecordingId(RecordingOrigin.LAN,recording,project,project,uploadIdFor(other,project)) }
        assertThrows(IllegalArgumentException::class.java) { lanUploadRecordingId(RecordingOrigin.LAN_UNASSIGNED,recording,project,project,upload) }
        val noncanonical = "AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA"
        assertThrows(IllegalArgumentException::class.java) { lanUploadRecordingId(RecordingOrigin.LAN,noncanonical,project,project,uploadIdFor(noncanonical,project)) }
    }
}
