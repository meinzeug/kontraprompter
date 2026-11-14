package de.kontrakurs.kontraprompter.pairing

import org.junit.Assert.*
import org.junit.Test

class PeerJournalCodecTest {
    private val journal = PeerJournal("a".repeat(64), "11111111-1111-4111-8111-111111111111", "b".repeat(64), listOf(
        PeerBinding("33333333-3333-4333-8333-333333333333", "22222222-2222-4222-8222-222222222222", "c".repeat(64), RelayRole.CAMERA, 7, PeerTrustState.REVOKED)
    ))
    @Test fun roundTripPreservesRevocationAndIdentity() {
        assertEquals(journal, decodePeerJournal(encodePeerJournal(journal)))
    }
    @Test fun truncatedFutureAndTrailingDataFailClosed() {
        val bytes = encodePeerJournal(journal)
        for (size in listOf(0, 1, 7, bytes.size - 1))
            assertThrows(IllegalArgumentException::class.java) { decodePeerJournal(bytes.copyOf(size)) }
        val future = bytes.copyOf().also { it[7] = 2 }
        assertThrows(IllegalArgumentException::class.java) { decodePeerJournal(future) }
        assertThrows(IllegalArgumentException::class.java) { decodePeerJournal(bytes + byteArrayOf(0)) }
    }
    @Test fun oversizedAndForgedIdentityFailClosed() {
        assertThrows(IllegalArgumentException::class.java) { decodePeerJournal(ByteArray(MAX_PEER_JOURNAL_BYTES + 1)) }
        assertThrows(IllegalArgumentException::class.java) { encodePeerJournal(journal.copy(localDeviceId = "not-a-uuid")) }
    }
}
