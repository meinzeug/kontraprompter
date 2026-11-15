package de.kontrakurs.kontraprompter.camera

/**
 * Test-only correlation gate. It does not authenticate the supplied session or
 * channel and has no production call site. The eventual adapter must bind the
 * opaque marker to the approved channel at the actual send/receive boundary.
 */
internal class WebCameraStatusProbe {
    internal data class Binding(val owner: String, val browserSession: String, val deviceSession: String, val prompterId: String, val cameraId: String, val epoch: String, val connection: Any)
    internal data class CameraStatus(val phase: String, val recordingId: String?, val front: Boolean?)
    private data class Pending(val binding: Binding, val requestId: String, val startNs: Long)
    private var pending: Pending? = null
    private val seen = mutableSetOf<String>()
    private val uuid = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
    private val digest = Regex("^[0-9a-f]{64}$")
    private val phases = setOf("idle", "preparing", "recording", "paused", "finalizing", "ready", "partial", "failed")
    private val maximumNs = 10_000_000_000L
    private fun valid(binding: Binding): Boolean = binding.owner.matches(digest) && binding.browserSession.matches(digest) &&
        binding.deviceSession.matches(digest) && binding.prompterId.matches(uuid) && binding.cameraId.matches(uuid) &&
        binding.prompterId != binding.cameraId && binding.epoch.matches(uuid)
    private fun matches(a: Binding, b: Binding): Boolean = a.owner == b.owner && a.browserSession == b.browserSession &&
        a.deviceSession == b.deviceSession && a.prompterId == b.prompterId && a.cameraId == b.cameraId && a.epoch == b.epoch && a.connection === b.connection
    private fun live(item: Pending, nowNs: Long): Boolean = nowNs >= item.startNs && nowNs - item.startNs < maximumNs

    /** The same request may not be replayed or migrated onto a different link. */
    @Synchronized fun begin(binding: Binding, requestId: String, nowNs: Long): Boolean {
        if (!valid(binding) || !requestId.matches(uuid) || nowNs < 0 || requestId in seen || seen.size >= 128) return false
        pending?.let { if (!live(it, nowNs)) pending = null }
        if (pending != null) return false
        seen.add(requestId)
        pending = Pending(binding, requestId, nowNs)
        return true
    }

    @Synchronized fun accept(binding: Binding, requestId: String, nowNs: Long, status: CameraStatus): CameraStatus? {
        val item = pending ?: return null
        if (!matches(item.binding, binding) || item.requestId != requestId) return null
        if (!live(item, nowNs)) { pending = null; return null }
        if (status.phase !in phases || (status.recordingId != null && !status.recordingId.matches(uuid)) ||
            (status.phase in setOf("preparing", "recording", "paused", "finalizing") && status.recordingId == null)) return null
        pending = null
        return status
    }

    /** Session logout, pairing replacement or disconnect invalidates the pending query. */
    @Synchronized fun invalidate(binding: Binding) {
        val item = pending ?: return
        if (matches(item.binding, binding)) pending = null
    }
}
