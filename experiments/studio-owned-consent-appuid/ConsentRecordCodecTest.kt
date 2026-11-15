package de.kontrakurs.kontraprompter.cloud

import org.junit.Assert.*
import org.junit.Test

class ConsentRecordCodecTest {
    private val id="2b8b26bc-d809-4f67-8759-028bb5891001"
    private val active=StudioOwnedConsentRecord(id,"a".repeat(64),"b".repeat(64),1,false)
    @Test fun `fixed record round trips without password or arbitrary fields`() {
        val raw=ConsentRecordCodec.encode(active);assertEquals(active,ConsentRecordCodec.decode(raw))
        val text=raw.toString(Charsets.US_ASCII);assertEquals(6,text.count { it=='\n' });assertFalse(text.contains("password"));assertFalse(text.contains("cookie"))
        assertThrows(IllegalArgumentException::class.java) { ConsentRecordCodec.decode(raw+"password\n".toByteArray()) }
    }
    @Test fun `corrupt, incomplete and noncanonical records are rejected`() {
        val text=ConsentRecordCodec.encode(active).toString(Charsets.US_ASCII)
        for(bad in listOf(text.dropLast(1),text.replace("\n1\n0\n","\n01\n0\n"),text.replace("\n1\n0\n","\n1\nfalse\n"),text.replace("a".repeat(64),"A".repeat(64)),"x".repeat(513)))
            assertThrows(Exception::class.java) { ConsentRecordCodec.decode(bad.toByteArray()) }
    }
    @Test fun `revision identity and sticky revocation are enforced`() {
        val revoked=active.copy(revision=2,revoked=true)
        assertTrue(ConsentRecordCodec.permittedTransition(null,active));assertTrue(ConsentRecordCodec.permittedTransition(active,revoked))
        assertFalse(ConsentRecordCodec.permittedTransition(null,revoked));assertFalse(ConsentRecordCodec.permittedTransition(revoked,active.copy(revision=3)))
        assertFalse(ConsentRecordCodec.permittedTransition(active,active.copy(revision=3)));assertFalse(ConsentRecordCodec.permittedTransition(active,active.copy(revision=2,ownerHash="c".repeat(64))))
    }
}
