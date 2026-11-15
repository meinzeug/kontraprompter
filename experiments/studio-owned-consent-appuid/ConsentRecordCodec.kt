package de.kontrakurs.kontraprompter.cloud

import java.util.UUID

/** Fixed, nonsecret format. No arbitrary fields, tokens, cookies or passwords. */
internal object ConsentRecordCodec {
    private val digest=Regex("^[0-9a-f]{64}$")
    fun valid(row: StudioOwnedConsentRecord): Boolean = runCatching {
        UUID.fromString(row.deviceId).toString()==row.deviceId && row.ownerHash.matches(digest) &&
            row.workspaceHash.matches(digest) && row.revision in 1..Long.MAX_VALUE
    }.getOrDefault(false)
    fun encode(row: StudioOwnedConsentRecord): ByteArray {
        require(valid(row))
        return "v1\n${row.deviceId}\n${row.ownerHash}\n${row.workspaceHash}\n${row.revision}\n${if(row.revoked) 1 else 0}\n".toByteArray(Charsets.US_ASCII)
    }
    fun decode(bytes: ByteArray): StudioOwnedConsentRecord {
        require(bytes.size in 1..512 && bytes.all { it.toInt() in 0..127 })
        val fields=bytes.toString(Charsets.US_ASCII).split('\n')
        require(fields.size==7 && fields[0]=="v1" && fields[6].isEmpty())
        require(fields[4].matches(Regex("^[1-9][0-9]{0,18}$")) && fields[5] in setOf("0","1"))
        val revision=fields[4].toLongOrNull() ?: error("Invalid consent revision")
        return StudioOwnedConsentRecord(fields[1],fields[2],fields[3],revision,fields[5]=="1").also { require(valid(it)) }
    }
    fun permittedTransition(old: StudioOwnedConsentRecord?,next: StudioOwnedConsentRecord): Boolean {
        if(!valid(next))return false
        if(old==null)return next.revision==1L && !next.revoked
        return valid(old) && old.revision<Long.MAX_VALUE && next.revision==old.revision+1 &&
            old.deviceId==next.deviceId && old.ownerHash==next.ownerHash && old.workspaceHash==next.workspaceHash &&
            (!old.revoked || next.revoked)
    }
}
