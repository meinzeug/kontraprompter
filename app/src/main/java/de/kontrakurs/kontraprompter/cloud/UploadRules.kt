package de.kontrakurs.kontraprompter.cloud

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

/** Stable across retries even if the local upload journal is lost in a crash. */
internal fun uploadIdFor(recordingId: String, projectId: String): String {
    val recording = UUID.fromString(recordingId).toString()
    val project = UUID.fromString(projectId).toString()
    val bytes = MessageDigest.getInstance("SHA-256").digest("kontraprompter-upload-v1\u0000$recording\u0000$project".toByteArray(Charsets.UTF_8))
    bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x80).toByte()
    bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
    val data = ByteBuffer.wrap(bytes)
    return UUID(data.long, data.long).toString()
}

/** A server response is an acknowledgement only for the exact submitted bytes and destination. */
internal data class UploadReceipt(val id: String, val projectId: String, val sizeBytes: Long, val sha256: String)
internal fun receiptMatches(entry: UploadEntry, receipt: UploadReceipt): Boolean =
    receipt.id == entry.uploadId && receipt.projectId == entry.projectId && receipt.sizeBytes == entry.size && receipt.sha256 == entry.sha256

/** A stale status for another recording or destination must never be displayed as current. */
internal fun confirmedFor(status: CloudUploadState, recordingId: String, projectId: String): Boolean =
    status is CloudUploadState.Confirmed && status.recordingId == recordingId && status.projectId == projectId

/** A continuation attempt must wait for its own durable intent transition. */
internal fun showCoordinatorConfirmation(status: CloudUploadState, recordingId: String, projectId: String): Boolean =
    status is CloudUploadState.Confirmed && !status.continuation && confirmedFor(status, recordingId, projectId)

internal fun preserveActiveUpload(current: CloudUploadState, failure: CloudUploadState.Failed): CloudUploadState =
    if (current is CloudUploadState.Running) current else failure

internal fun cameraUploadConflictKind(code: String?, cameraClaim: Boolean): String = if (!cameraClaim) "conflict" else when (code) {
    "camera_manifest_pending" -> "manifest_pending"
    "camera_manifest_mismatch" -> "manifest_mismatch"
    "camera_manifest_partial" -> "manifest_partial"
    "camera_upload_busy" -> "busy"
    else -> "conflict"
}

internal data class JournalLookup(val recordingId: String, val projectId: String, val phase: String?, val failed: Boolean)
internal fun matchingJournal(value: JournalLookup?, recordingId: String, projectId: String): JournalLookup? =
    value?.takeIf { it.recordingId == recordingId && it.projectId == projectId }
