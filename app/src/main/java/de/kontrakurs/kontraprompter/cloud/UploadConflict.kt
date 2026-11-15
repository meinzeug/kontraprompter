package de.kontrakurs.kontraprompter.cloud

import org.json.JSONObject
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

internal const val RECORDING_DELETED = "recording_deleted"
internal const val RECORDING_DELETED_NOT_SAVED = "Der Server meldet die Upload-ID als gelöscht. Das Original bleibt lokal. Für dieses Skript ist eine neue Aufnahme nötig. Der Hinweis ist nicht dauerhaft gespeichert; nach Neustart ist ein erneuter Versuch möglich."
internal const val RECORDING_DELETED_MESSAGE = "Der Server meldet: Die für diesen Uploadauftrag verwendete Server-Aufnahme-ID wurde gelöscht. Das Original bleibt lokal. Für dieses Skript ist eine neue Aufnahme mit neuer Aufnahme-ID nötig."

// The deployed tombstone response has exactly code and error fields. Require
// strict JSON syntax for that special case; unfamiliar shapes stay generic.
private val jsonSpace = "[ \t\r\n]*"
private val jsonString = "\"(?:[^\"\\\\\u0000-\u001f]|\\\\(?:[\"\\\\/bfnrt]|u[0-9a-fA-F]{4}))*\""
private val deletedPayload = Regex("$jsonSpace\\{$jsonSpace(?:\"code\"$jsonSpace:$jsonSpace\"recording_deleted\"$jsonSpace,$jsonSpace\"error\"$jsonSpace:$jsonSpace$jsonString|\"error\"$jsonSpace:$jsonSpace$jsonString$jsonSpace,$jsonSpace\"code\"$jsonSpace:$jsonSpace\"recording_deleted\")$jsonSpace\\}$jsonSpace")

/** Untrusted error bodies are optional, bounded and must contain a literal string code. */
internal fun uploadConflictCode(input: InputStream?): String? = runCatching {
    val stream = input ?: return@runCatching null
    val bytes = stream.use { source ->
        val output = ByteArrayOutputStream(); val block = ByteArray(1024)
        while (true) { val count = source.read(block); if (count < 0) break; check(output.size() + count <= 16_384); output.write(block, 0, count) }
        output.toByteArray()
    }
    // Preserve the former lenient UTF-8 parsing for existing 409 codes.
    val text = bytes.toString(Charsets.UTF_8); check(text.length <= 4096)
    val code = JSONObject(text).optString("code", "")
    if (code == RECORDING_DELETED) {
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        val strictText = decoder.decode(ByteBuffer.wrap(bytes)).toString()
        if (!deletedPayload.matches(strictText)) null else code
    } else code
}.getOrNull()

internal fun uploadConflictKind(code: String?, cameraClaim: Boolean): String =
    if (code == RECORDING_DELETED) RECORDING_DELETED else cameraUploadConflictKind(code, cameraClaim)

/** Unknown or malformed optional reasons retain the existing generic conflict behaviour. */
internal fun uploadConflictReason(value: Any?, phase: String): String? =
    if (phase == "conflict" && value is String && value == RECORDING_DELETED) RECORDING_DELETED else null

internal fun uploadBlockedMessage(reason: String?): String =
    if (reason == RECORDING_DELETED) RECORDING_DELETED_MESSAGE else "Aufnahme oder Skript muss geprüft werden. Das Original bleibt lokal."

/** Tombstones apply to one exact local recording and destination; other projects remain eligible. */
internal fun deletedUploadBlocked(entry: UploadEntry?, recordingId: String, projectId: String): Boolean =
    entry?.recordingId == recordingId && entry.projectId == projectId && entry.phase == "conflict" && entry.conflictReason == RECORDING_DELETED

internal fun blocksManualContinuation(kind: String): Boolean =
    kind in setOf("project_missing", "conflict", "manifest_mismatch", "manifest_partial", RECORDING_DELETED)

internal fun checkDeletedResponseSession(code: String?, current: Boolean) {
    if (code == RECORDING_DELETED && !current) throw UploadException("auth", "Studio-Sitzung geändert; Serverantwort prüfen. Die Aufnahme bleibt lokal.")
}

internal fun deletedMessageAfterWrite(saved: Boolean): String =
    if (saved) RECORDING_DELETED_MESSAGE else RECORDING_DELETED_NOT_SAVED

internal fun requireUploadSession(expected: String, current: String?) {
    if (expected != current) throw UploadException("auth", "Studio-Sitzung geändert; Serverbeleg prüfen. Die Aufnahme bleibt lokal.")
}
