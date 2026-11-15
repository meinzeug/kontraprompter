package de.kontrakurs.kontraprompter.video

import java.util.Base64

/** Test-only data format. This does not prove identity, pair approval, freshness or authority. */
internal data class WebStatusProofBinding(
    val owner: String, val browserSession: String, val deviceSession: String,
    val cameraId: String, val prompterId: String, val pairEpoch: String,
    val requestId: String, val nonce: String,
)

internal object WebStatusProofTranscript {
    private val uuid = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
    private val hash = Regex("^[0-9a-f]{64}$")
    private val nonce = Regex("^[A-Za-z0-9_-]{43}$")
    private val phases = setOf("idle", "preparing", "recording", "paused", "finalizing", "ready", "partial", "failed")
    private val needsTake = setOf("preparing", "recording", "paused", "finalizing", "ready", "partial")
    private const val MAX_SAFE_JSON_INTEGER = 9_007_199_254_740_991L

    fun encode(binding: WebStatusProofBinding, status: PairedStatusObservation): ByteArray? {
        if (!listOf(binding.owner, binding.browserSession, binding.deviceSession).all(hash::matches) ||
            !listOf(binding.cameraId, binding.prompterId, binding.pairEpoch, binding.requestId).all(uuid::matches) ||
            binding.cameraId == binding.prompterId || !nonce.matches(binding.nonce)) return null
        val bytes = runCatching { Base64.getUrlDecoder().decode(binding.nonce) }.getOrNull() ?: return null
        if (bytes.size != 32 || Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) != binding.nonce) return null
        if (status.revision !in 1..MAX_SAFE_JSON_INTEGER || status.phase !in phases ||
            (status.recordingId != null && !uuid.matches(status.recordingId)) ||
            (status.phase in needsTake && status.recordingId == null) || (status.phase == "idle" && status.recordingId != null) ||
            (status.projectId != null && !uuid.matches(status.projectId)) || (!status.projectKnown && status.projectId != null) ||
            (status.projectKnown && status.recordingId == null)) return null
        return listOf(
            "videomaker-camera-status-proof/v1", binding.owner, binding.browserSession, binding.deviceSession,
            binding.cameraId, binding.prompterId, binding.pairEpoch, binding.requestId, binding.nonce,
            status.revision.toString(), status.phase, status.recordingId ?: "-", if (status.projectKnown) "1" else "0",
            status.projectId ?: "-", when (status.cameraFront) { true -> "1"; false -> "0"; null -> "-" }, "",
        ).joinToString("\n").toByteArray(Charsets.UTF_8)
    }
}
