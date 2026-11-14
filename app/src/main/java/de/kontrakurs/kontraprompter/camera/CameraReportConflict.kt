package de.kontrakurs.kontraprompter.camera

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Only a response bound to the precise in-flight report permits quarantine. */
internal fun matchesLateReportConflict(deviceId: String, request: CameraPollRequest, fields: Map<String, Any?>): Boolean {
    val report = request.report
    if (report.phase !in setOf("partial", "error") || report.recordingId == null || report.projectId == null) return false
    if (report.phase == "partial" && (report.sizeBytes == null || report.sha256 == null)) return false
    if (report.phase == "error" && (report.sizeBytes != null || report.sha256 != null)) return false
    val sequence = fields["sequence"]
    if (sequence !is Int && sequence !is Long) return false
    val size = fields["sizeBytes"]
    if (size != null && size !is Int && size !is Long) return false
    return fields["code"] == "camera_late_report_conflict" && fields["deviceId"] == deviceId &&
        fields["recordingId"] == report.recordingId && fields["projectId"] == report.projectId &&
        fields["phase"] == report.phase && (sequence as Number).toLong() == request.sequence &&
        (size as Number?)?.toLong() == report.sizeBytes && fields["sha256"] == report.sha256
}

internal class CameraLateReportConflictException(val deviceId: String) : IOException("Ein Kamerabericht ist ungeklärt und benötigt Prüfung.")

internal fun requireCameraConflictJournalIdentity(expectedDeviceId: String, responseDeviceId: String) {
    check(expectedDeviceId == responseDeviceId) { "Kamerabericht gehört zu einer anderen Geräteidentität." }
}

/** Drain the transport and persist its typed conflict before returning to a cancelled caller. */
internal suspend fun <T> exchangeWithLateReportConflict(exchange: () -> T, persist: suspend (CameraLateReportConflictException) -> Unit, onUnsafe: suspend () -> Unit): T? =
    withContext(NonCancellable + Dispatchers.IO) {
        try { exchange() }
        catch (conflict: CameraLateReportConflictException) { persistLateReportConflict({ persist(conflict) }, onUnsafe); null }
    }

/** A response already received is journaled even if logout cancels the poll. */
internal suspend fun persistLateReportConflict(persist: suspend () -> Unit, onUnsafe: suspend () -> Unit) = withContext(NonCancellable) {
    try { persist() }
    catch (error: Exception) {
        try { onUnsafe() } catch (_: Exception) {}
        throw error
    }
}
