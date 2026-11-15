package de.kontrakurs.kontraprompter.cloud

/** Nonsecret durable state only. The production persistence adapter does not exist. */
internal data class StudioOwnedConsentRecord(
    val deviceId: String, val ownerHash: String, val workspaceHash: String,
    val revision: Long, val revoked: Boolean,
)

/** Atomic, crash-durable compare-and-set is a requirement on any real implementation. */
internal interface StudioOwnedConsentStore {
    fun read(): StudioOwnedConsentRecord?
    fun compareAndSet(expected: StudioOwnedConsentRecord?, update: StudioOwnedConsentRecord): Boolean
}

