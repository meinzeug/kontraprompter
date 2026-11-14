package de.kontrakurs.kontraprompter.video

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import org.webrtc.*
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Owns only the second peer. The factory, track, EGL and camera belong to RtcVideo. */
internal class BrowserPreviewPeer(
    private val context: Context,
    factory: PeerConnectionFactory,
    track: VideoTrack,
    private val post: (() -> Unit) -> Unit,
    private val emit: (String, String, String?, Int?) -> Unit,
) : AutoCloseable {
    private val candidateWorker = ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, ArrayBlockingQueue(128), { task ->
        Thread(task, "preview-candidate").apply { isDaemon = true }
    }, ThreadPoolExecutor.AbortPolicy())
    private var connection: PeerConnection? = null
    private var closed = false
    private var remoteSet = false
    private var answerSent = false
    private var gatheringFinished = false
    private val pendingRemote = mutableListOf<IceCandidate>()
    private val pendingLocal = mutableListOf<IceCandidate>()
    private val manager = context.getSystemService(ConnectivityManager::class.java) ?: error("Netzwerk nicht verfügbar")
    private val network = PreviewCandidatePolicy.snapshot(context) ?: error("Lokales Netzwerk nicht verfügbar")
    private var callbackRegistered = false
    // Latch on the callback thread: examining only the later executor state can miss a
    // transient change which has already reverted by the time the queue is drained.
    private val networkGuard = PreviewNetworkGuard(network) { post { fail() } }
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(value: Network) { if (value != network.network) networkGuard.invalidate() }
        override fun onLost(value: Network) { networkGuard.invalidate() }
        override fun onCapabilitiesChanged(value: Network, caps: NetworkCapabilities) {
            if (value != network.network || !PreviewCandidatePolicy.permitted(caps)) networkGuard.invalidate()
        }
        override fun onLinkPropertiesChanged(value: Network, properties: LinkProperties) {
            val links = PreviewCandidatePolicy.linkSnapshot(properties)
            networkGuard.observe(if (links == null) null else PreviewCandidatePolicy.NetworkSnapshot(value, links))
        }
    }

    init {
        try {
            manager.registerDefaultNetworkCallback(networkCallback); callbackRegistered = true
            check(networkGuard.isValid() && PreviewCandidatePolicy.snapshot(context) == network) { "Lokales Netzwerk hat sich geändert" }
            val config = PeerConnection.RTCConfiguration(emptyList()).apply { sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN }
            connection = factory.createPeerConnection(config, observer()) ?: error("Vorschau-Peer nicht verfügbar")
            check(connection!!.addTrack(track, listOf("kontra-preview")) != null) { "Vorschauspur nicht verfügbar" }
        } catch (error: Exception) { close(); throw error }
    }

    fun offer(sdp: String) {
        check(!closed && validDescription(sdp, "recvonly")) { "Ungültiges Vorschau-Angebot" }
        connection?.setRemoteDescription(object : PreviewSdpObserver() {
            override fun onSetSuccess() = post {
                if (!closed) {
                    if (!networkGuard.isValid() || PreviewCandidatePolicy.snapshot(context) != network) { fail(); return@post }
                    remoteSet = true
                    pendingRemote.forEach { connection?.addIceCandidate(it) }; pendingRemote.clear()
                    connection?.createAnswer(object : PreviewSdpObserver() {
                        override fun onCreateSuccess(description: SessionDescription) = post {
                            if (!closed) {
                                if (!validDescription(description.description, "sendonly")) { fail(); return@post }
                                connection?.setLocalDescription(object : PreviewSdpObserver() {
                                    override fun onSetSuccess() = post {
                                        if (!closed) {
                                            if (!networkGuard.isValid() || PreviewCandidatePolicy.snapshot(context) != network) { fail(); return@post }
                                            emit("answer", description.description, null, null)
                                            answerSent = true
                                            pendingLocal.forEach(::emitCandidate); pendingLocal.clear()
                                            if (gatheringFinished) emit("ice_end", "", null, null)
                                        }
                                    }
                                    override fun onSetFailure(error: String) = post { fail() }
                                }, description)
                            }
                        }
                        override fun onCreateFailure(error: String) = post { fail() }
                    }, MediaConstraints())
                }
            }
            override fun onSetFailure(error: String) = post { fail() }
        }, SessionDescription(SessionDescription.Type.OFFER, sdp))
    }

    fun candidate(data: String, mid: String?, line: Int?) {
        if (closed || (mid == null && line == null) || (line != null && line !in 0..255) || (mid?.length ?: 0) > 64) return
        runCatching { candidateWorker.execute {
            if (!networkGuard.isValid()) return@execute
            val checked = PreviewCandidatePolicy.checked(context, data, network) ?: return@execute
            post {
                if (!closed) {
                    if (!networkGuard.isValid() || PreviewCandidatePolicy.snapshot(context) != network) { fail(); return@post }
                    val c = IceCandidate(mid ?: "", line ?: 0, checked)
                    if (remoteSet) connection?.addIceCandidate(c) else if (pendingRemote.size < 64) pendingRemote.add(c) else fail()
                }
            }
        } }
    }

    private fun emitCandidate(candidate: IceCandidate) = emit("ice", candidate.sdp, candidate.sdpMid, candidate.sdpMLineIndex)
    private fun observer() = object : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
            if (state == PeerConnection.IceConnectionState.FAILED || state == PeerConnection.IceConnectionState.CLOSED) post { fail() }
        }
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
            // The queue barrier follows candidate validation, so ICE-end cannot overtake candidates.
            if (state == PeerConnection.IceGatheringState.COMPLETE) runCatching { candidateWorker.execute {
                post { if (!closed) { gatheringFinished = true; if (answerSent) emit("ice_end", "", null, null) } }
            } }.onFailure { post { fail() } }
        }
        override fun onIceCandidate(candidate: IceCandidate) {
            runCatching { candidateWorker.execute {
                if (!networkGuard.isValid()) return@execute
                val checked = PreviewCandidatePolicy.checked(context, candidate.sdp, network) ?: return@execute
                post {
                    if (!closed) {
                        if (!networkGuard.isValid() || PreviewCandidatePolicy.snapshot(context) != network) { fail(); return@post }
                        val safe = IceCandidate(candidate.sdpMid, candidate.sdpMLineIndex, checked)
                        if (answerSent) emitCandidate(safe) else if (pendingLocal.size < 64) pendingLocal.add(safe) else fail()
                    }
                }
            } }
        }
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
        override fun onAddStream(stream: MediaStream) = Unit
        override fun onRemoveStream(stream: MediaStream) = Unit
        override fun onDataChannel(channel: DataChannel) = Unit
        override fun onRenegotiationNeeded() = Unit
    }

    private fun fail() { if (!closed) { emit("end", "", null, null); close() } }
    override fun close() {
        if (closed) return
        closed = true
        if (callbackRegistered) { runCatching { manager.unregisterNetworkCallback(networkCallback) }; callbackRegistered = false }
        candidateWorker.shutdownNow()
        pendingRemote.clear(); pendingLocal.clear()
        connection?.close(); connection?.dispose(); connection = null
    }
}

private open class PreviewSdpObserver : SdpObserver {
    override fun onCreateSuccess(description: SessionDescription) = Unit
    override fun onSetSuccess() = Unit
    override fun onCreateFailure(error: String) = Unit
    override fun onSetFailure(error: String) = Unit
}

internal fun validDescription(sdp: String, direction: String): Boolean {
    if (sdp.toByteArray(Charsets.UTF_8).size > 64 * 1024 || !sdp.startsWith("v=0") || sdp.any { it.code < 32 && it != '\r' && it != '\n' && it != '\t' }) return false
    val lines = sdp.split(Regex("\\r?\\n"))
    val media = lines.filter { it.startsWith("m=") }
    if (media.size != 1 || !media.single().startsWith("m=video ", true)) return false
    if (lines.any { it.startsWith("a=candidate:", true) || it.startsWith("a=remote-candidates:", true) }) return false
    val directions = lines.filter { it in setOf("a=sendrecv", "a=sendonly", "a=recvonly", "a=inactive") }
    if (directions != listOf("a=$direction")) return false
    if (lines.any { it.startsWith("c=", true) && it != "c=IN IP4 0.0.0.0" && it != "c=IN IP6 ::" }) return false
    return lines.none { it.startsWith("a=rtcp:", true) && !it.matches(Regex("a=rtcp:[0-9]+(?: IN IP4 0\\.0\\.0\\.0)?", RegexOption.IGNORE_CASE)) }
}
