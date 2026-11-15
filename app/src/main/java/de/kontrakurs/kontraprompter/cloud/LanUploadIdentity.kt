package de.kontrakurs.kontraprompter.cloud

import de.kontrakurs.kontraprompter.video.RecordingOrigin
import java.net.HttpURLConnection
import java.util.UUID

/** Identity is derived from the retained original and journal, never from the LAN peer. */
internal fun lanUploadRecordingId(origin: RecordingOrigin, recordingId: String, boundProject: String?, targetProject: String, uploadId: String): String? {
    if (origin != RecordingOrigin.LAN && origin != RecordingOrigin.LAN_UNASSIGNED) return null
    require(RecordingOrigin.read(origin.wire, boundProject) == origin)
    require(UUID.fromString(recordingId).toString() == recordingId && UUID.fromString(targetProject).toString() == targetProject)
    require(origin != RecordingOrigin.LAN || boundProject == targetProject) { "LAN-Aufnahme gehört zu einem anderen Skript." }
    require(UUID.fromString(uploadId).toString() == uploadId && uploadIdFor(recordingId, targetProject) == uploadId)
    return recordingId
}

/** The header is additional to, never a replacement for, normal HTTPS session authorization. */
internal fun applyLanUploadIdentity(connection: HttpURLConnection, recordingId: String?) {
    if (recordingId != null) connection.setRequestProperty("X-Lan-Recording-Id", recordingId)
}
