package de.kontrakurs.kontraprompter.video

import android.content.Context
import de.kontrakurs.kontraprompter.network.LanSession
import org.json.JSONObject
import org.webrtc.*
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Preview only: owns the camera. It does not start or pretend to start an MP4 recording. */
class RtcVideo(private val context: Context, private val host: Boolean, private val lan: LanSession, private val cameraLease: Long? = null, private val preferFrontCamera: Boolean = true) : AutoCloseable {
    private class DirectPath(val addresses: LanSession.SocketAddresses, val interfaceName: String)
    @Volatile private var directPath: DirectPath? = null
    private sealed interface RouteDecision {
        data class Direct(val path: DirectPath) : RouteDecision
        data object NormalWifi : RouteDecision
        data object Unknown : RouteDecision
    }

    @Suppress("DEPRECATION") // TYPE_WIFI excludes a system-reported WIFI_P2P network on API 26+.
    private fun routeFor(addresses: LanSession.SocketAddresses): RouteDecision = runCatching {
        val local = addresses.local
        val remote = addresses.remote
        val net = java.net.NetworkInterface.getByInetAddress(local) ?: return RouteDecision.Unknown
        if (!net.isUp || net.isLoopback || !java.util.Collections.list(net.inetAddresses).contains(local)) return RouteDecision.Unknown
        if (p2pInterfaceMatches(net.name, true, false, local, java.util.Collections.list(net.inetAddresses))) {
            if (remote !is java.net.Inet4Address || remote == local || remote.isAnyLocalAddress || remote.isLoopbackAddress || remote.isMulticastAddress) return RouteDecision.Unknown
            if (!lan.isApprovedConnection(addresses.marker)) return RouteDecision.Unknown
            return RouteDecision.Direct(DirectPath(addresses, net.name))
        }
        // Match the actual socket against Android Wi-Fi networks, independent of
        // default Internet routing. Multiple or unverifiable matches fail closed.
        val manager = context.getSystemService(android.net.ConnectivityManager::class.java) ?: return RouteDecision.Unknown
        fun matchesWifi(network: android.net.Network): Boolean {
            val caps = manager.getNetworkCapabilities(network) ?: return false
            val properties = manager.getLinkProperties(network) ?: return false
            return isMatchingOrdinaryWifi(net.name, properties.interfaceName, caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI),
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN), manager.getNetworkInfo(network)?.type == android.net.ConnectivityManager.TYPE_WIFI,
                local, properties.linkAddresses.map { it.address })
        }
        val matches = manager.allNetworks.filter(::matchesWifi)
        if (matches.size != 1 || !matchesWifi(matches.single()) || !manager.allNetworks.contains(matches.single()) || !lan.isApprovedConnection(addresses.marker)) return RouteDecision.Unknown
        RouteDecision.NormalWifi
    }.getOrDefault(RouteDecision.Unknown)

    private fun directPathCurrent(path: DirectPath): Boolean = runCatching {
        if (!lan.isApprovedConnection(path.addresses.marker)) return false
        val net = java.net.NetworkInterface.getByInetAddress(path.addresses.local) ?: return false
        net.name == path.interfaceName && p2pInterfaceMatches(net.name, net.isUp, net.isLoopback, path.addresses.local, java.util.Collections.list(net.inetAddresses)) &&
            lan.isApprovedConnection(path.addresses.marker)
    }.getOrDefault(false)
    companion object {
        @Volatile private var initialized = false
        private val cameraGate = RecordingResourceGate()
        private val browserPreviewSafety = PreviewPeerSafety()
        internal fun tryAcquireCamera(): Long? = cameraGate.tryAcquire()
        internal fun releaseCamera(lease: Long) = cameraGate.release(lease)
        fun cameraAvailable(): Boolean = cameraGate.isAvailable()
        @Synchronized private fun initialize(context: Context) {
            if (!initialized) { PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context.applicationContext).createInitializationOptions()); initialized = true }
        }
    }
    val egl: EglBase = EglBase.create()
    private val executor = Executors.newSingleThreadExecutor()
    private val closed = AtomicBoolean(false)
    private val primaryGeneration = PrimaryPeerGeneration()
    // Captured with the pipeline object, before LiveSession releases its ownership lock.
    private val initialPrimaryGeneration = primaryGeneration.current()
    private var factory: PeerConnectionFactory? = null
    private var peer: PeerConnection? = null
    private val directPeerStop = DirectPeerStop(
        submit = { action -> runCatching { executor.execute { action() } }.isSuccess },
        // Deliberately do not dispose peer, track, camera or recorder here.
        closePeer = { peer?.let { it.close(); true } ?: false },
        onState = { state -> onDirectPeerStop?.invoke(state) },
    )
    private var source: VideoSource? = null
    private var localTrack: VideoTrack? = null
    private var camera: CameraVideoCapturer? = null
    private var textureHelper: SurfaceTextureHelper? = null
    private var remoteTrack: VideoTrack? = null
    @Volatile internal var onCameraFailure: ((String) -> Unit)? = null
    @Volatile internal var onCameraSelection: ((Boolean?, Boolean) -> Unit)? = null
    @Volatile internal var onDirectPeerStop: ((DirectPeerStop.State) -> Unit)? = null
    private val sinks = mutableSetOf<VideoSink>()
    private val previewViews = mutableSetOf<VideoView>()
    private var pipelineClosed = false
    private var eglReleased = false
    private val pendingCandidates = mutableListOf<IceCandidate>()
    private var remoteDescriptionSet = false
    private var browserPreview: BrowserPreviewPeer? = null
    private var browserPreviewId: String? = null
    @Volatile private var cameraFrameAvailable = false

    fun start() {
        // A disconnect can invalidate the initial generation before start() is called.
        val generation = initialPrimaryGeneration
        executePrimary(generation) {
            try {
                initialize(context)
                val addresses = lan.approvedSocketAddresses() ?: return@executePrimary
                val decision = routeFor(addresses)
                if (!lan.isApprovedConnection(addresses.marker)) return@executePrimary
                if (decision == RouteDecision.Unknown) { lan.report("Videoverbindung nicht verfügbar", "Netzwerkschnittstelle konnte nicht sicher zugeordnet werden."); return@executePrimary }
                val direct = (decision as? RouteDecision.Direct)?.path
                directPath = direct
                if (direct != null && (!primaryGeneration.isCurrent(generation) || !lan.isApprovedConnection(addresses.marker))) { directPeerStop.request(); return@executePrimary }
                val builder = PeerConnectionFactory.builder()
                if (direct != null) builder.setOptions(PeerConnectionFactory.Options().apply {
                    disableNetworkMonitor = true
                    networkIgnoreMask = PeerConnectionFactory.Options.ADAPTER_TYPE_LOOPBACK
                })
                factory = builder
                    .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
                    .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext)).createPeerConnectionFactory()
                val config = PeerConnection.RTCConfiguration(emptyList()).apply { sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN }
                peer = factory!!.createPeerConnection(config, observer(generation)) ?: error("PeerConnection nicht verfügbar")
                if (direct != null && (!primaryGeneration.isCurrent(generation) || !lan.isApprovedConnection(addresses.marker))) { directPeerStop.request(); return@executePrimary }
                if (host) {
                    val enumerator: CameraEnumerator = if (Camera2Enumerator.isSupported(context)) Camera2Enumerator(context) else Camera1Enumerator(false)
                    val name = chooseCamera(enumerator.deviceNames.toList(), preferFrontCamera, enumerator::isFrontFacing, enumerator::isBackFacing) ?: error("Keine Kamera verfügbar")
                    onCameraSelection?.invoke(if (enumerator.isFrontFacing(name)) true else if (enumerator.isBackFacing(name)) false else null, enumerator.deviceNames.any { enumerator.isFrontFacing(it) } && enumerator.deviceNames.any { enumerator.isBackFacing(it) })
                    camera = enumerator.createCapturer(name, cameraEvents()) ?: error("Kamera konnte nicht geöffnet werden")
                    textureHelper = SurfaceTextureHelper.create("KontraCamera", egl.eglBaseContext) ?: error("Kameraoberfläche nicht verfügbar")
                    source = factory!!.createVideoSource(false)
                    camera!!.initialize(textureHelper, context.applicationContext, source!!.capturerObserver)
                    val track = factory!!.createVideoTrack("camera", source)
                    synchronized(sinks) { localTrack = track; sinks.forEach { track.addSink(it) } }
                    peer!!.addTrack(track, listOf("kontra")) ?: error("Videospur konnte nicht hinzugefügt werden")
                    preferVp8()
                    camera!!.startCapture(640, 480, 20)
                    peer!!.createOffer(createDescriptionObserver(generation), MediaConstraints())
                }
            } catch (e: Exception) {
                if (directPath != null) { invalidatePrimaryPeer(); lan.report("Videofehler", "Direktverbindung konnte nicht gestartet werden.") }
                else lan.report("Videofehler", e.localizedMessage ?: "Video konnte nicht gestartet werden")
            }
        }
    }
    private fun preferVp8() {
        val p = peer ?: return
        val caps = factory?.getRtpSenderCapabilities(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO)?.codecs ?: return
        val vp8 = caps.filter { it.name.equals("VP8", true) }
        if (vp8.isNotEmpty()) p.transceivers.firstOrNull { it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO }?.setCodecPreferences(vp8 + caps.filterNot { it.name.equals("VP8", true) })
    }
    private fun cameraEvents() = object : CameraVideoCapturer.CameraEventsHandler {
        override fun onCameraError(error: String) { cameraFrameAvailable = false; stopAnyBrowserPreview(); lan.report("Kamerafehler", error); onCameraFailure?.invoke("Kamera meldet einen Fehler") }
        override fun onCameraDisconnected() { cameraFrameAvailable = false; stopAnyBrowserPreview(); lan.report("Kamerafehler", "Kamera wurde getrennt"); onCameraFailure?.invoke("Kamera wurde getrennt") }
        override fun onCameraFreezed(error: String) { cameraFrameAvailable = false; stopAnyBrowserPreview(); lan.report("Kamerafehler", error); onCameraFailure?.invoke("Kamerabild ist stehengeblieben") }
        override fun onCameraOpening(name: String) = Unit
        override fun onFirstFrameAvailable() { cameraFrameAvailable = true; lan.reportFirstCameraFrame() }
        override fun onCameraClosed() { cameraFrameAvailable = false; stopAnyBrowserPreview() }
    }
    private fun observer(generation: Long) = object : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) = executePrimary(generation) {
            directPath?.let { if (!directPathCurrent(it)) { invalidatePrimaryPeer(); lan.report("Videoverbindung unterbrochen", "Direktverbindung hat sich geändert."); return@executePrimary } }
            when (state) {
                PeerConnection.IceConnectionState.CONNECTED, PeerConnection.IceConnectionState.COMPLETED -> lan.report("Videoverbindung hergestellt")
                PeerConnection.IceConnectionState.FAILED -> lan.report("Videoverbindung fehlgeschlagen", "Gleiches lokales Netz prüfen; Hotspot oder WLAN kann Geräte isolieren.")
                PeerConnection.IceConnectionState.DISCONNECTED -> lan.report("Videoverbindung unterbrochen", "Lokales Netz prüfen")
                else -> Unit
            }
        }
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
        override fun onIceCandidate(c: IceCandidate) = executePrimary(generation) {
            val payload = JSONObject().put("mid", c.sdpMid).put("line", c.sdpMLineIndex).put("candidate", c.sdp).toString()
            val direct = directPath
            if (direct == null) lan.send("ice", payload)
            else if (!directPathCurrent(direct)) { invalidatePrimaryPeer(); lan.report("Videoverbindung unterbrochen", "Direktverbindung hat sich geändert.") }
            else if (p2pCandidateMatches(c.sdp, direct.addresses.local)) lan.sendOnApprovedConnection(direct.addresses.marker, "ice", payload)
        }
        override fun onIceCandidatesRemoved(c: Array<out IceCandidate>) = Unit
        override fun onAddStream(stream: MediaStream) = Unit
        override fun onRemoveStream(stream: MediaStream) = Unit
        override fun onDataChannel(channel: DataChannel) = Unit
        override fun onRenegotiationNeeded() = Unit
        override fun onTrack(transceiver: RtpTransceiver) {
            if (!primaryGeneration.isCurrent(generation)) return
            (transceiver.receiver.track() as? VideoTrack)?.let { track -> executePrimary(generation) { setRemoteTrack(track) } }
        }
    }
    private fun setRemoteTrack(track: VideoTrack) {
        synchronized(sinks) {
            if (remoteTrack === track) return
            sinks.forEach { remoteTrack?.removeSink(it) }
            remoteTrack = track
            sinks.forEach { track.addSink(it) }
        }
    }
    fun message(type: String, value: String) {
        val generation = primaryGeneration.current()
        executePrimary(generation) {
            if (!lan.state.value.ready) return@executePrimary
            directPath?.let { if (!directPathCurrent(it)) { invalidatePrimaryPeer(); lan.report("Videoverbindung unterbrochen", "Direktverbindung hat sich geändert."); return@executePrimary } }
            try {
                when (type) {
                    "sdp" -> {
                        val json = JSONObject(value)
                        val kind = when (json.getString("type")) { "offer" -> SessionDescription.Type.OFFER; "answer" -> SessionDescription.Type.ANSWER; else -> error("Ungültiger SDP-Typ") }
                        if ((host && kind != SessionDescription.Type.ANSWER) || (!host && kind != SessionDescription.Type.OFFER)) error("Unerwarteter SDP-Typ")
                        if (directPath != null && !p2pDescriptionSafe(json.getString("sdp"))) error("Ungültige Direktverbindungsbeschreibung")
                        peer?.setRemoteDescription(object : SimpleSdpObserver({ error -> reportSdpFailure(generation, error) }) {
                            override fun onSetSuccess() = executePrimary(generation) {
                                remoteDescriptionSet = true
                                pendingCandidates.forEach { peer?.addIceCandidate(it) }; pendingCandidates.clear()
                                if (kind == SessionDescription.Type.OFFER) peer?.createAnswer(createDescriptionObserver(generation), MediaConstraints())
                            }
                        }, SessionDescription(kind, json.getString("sdp")))
                    }
                    "ice" -> {
                        val j = JSONObject(value)
                        val candidate = IceCandidate(j.optString("mid", ""), j.getInt("line"), j.getString("candidate"))
                        directPath?.let { if (!p2pCandidateMatches(candidate.sdp, it.addresses.remote)) return@executePrimary }
                        if (remoteDescriptionSet) peer?.addIceCandidate(candidate) else if (pendingCandidates.size < 256) pendingCandidates.add(candidate) else error("Zu viele ICE-Kandidaten")
                    }
                }
            } catch (e: Exception) {
                if (directPath != null) { invalidatePrimaryPeer(); lan.report("Videofehler", "Ungültige Direktverbindungsnachricht.") }
                else lan.report("Videofehler", e.localizedMessage ?: "Ungültige Videonachricht")
            }
        }
    }
    private fun createDescriptionObserver(generation: Long) = object : SimpleSdpObserver({ error -> reportSdpFailure(generation, error) }) {
        override fun onCreateSuccess(description: SessionDescription) = executePrimary(generation) {
            directPath?.let { if (!directPathCurrent(it) || !p2pDescriptionSafe(description.description)) { invalidatePrimaryPeer(); lan.report("Videofehler", "Ungültige Direktverbindungsbeschreibung"); return@executePrimary } }
            peer?.setLocalDescription(object : SimpleSdpObserver({ error -> reportSdpFailure(generation, error) }) {
                override fun onSetSuccess() = executePrimary(generation) {
                    val payload = JSONObject().put("type", description.type.canonicalForm()).put("sdp", description.description).toString()
                    val direct = directPath
                    if (direct == null) lan.send("sdp", payload)
                    else if (directPathCurrent(direct)) lan.sendOnApprovedConnection(direct.addresses.marker, "sdp", payload)
                    else { invalidatePrimaryPeer(); lan.report("Videoverbindung unterbrochen", "Direktverbindung hat sich geändert.") }
                }
            }, description)
        }
    }
    private fun reportSdpFailure(generation: Long, error: String) = executePrimary(generation) {
        if (directPath != null) { invalidatePrimaryPeer(); lan.report("Videofehler", "Direktverbindungsbeschreibung wurde abgelehnt.") }
        else lan.report("Videofehler", error)
    }
    private fun executePrimary(generation: Long, action: () -> Unit) = execute {
        if (primaryGeneration.isCurrent(generation)) action()
    }
    internal fun invalidatePrimaryPeer() {
        primaryGeneration.invalidate()
        if (directPath != null) directPeerStop.request()
    }
    fun attach(sink: VideoSink) { synchronized(sinks) { if (!closed.get() && sinks.add(sink)) (if (host) localTrack else remoteTrack)?.addSink(sink) } }
    fun detach(sink: VideoSink) { synchronized(sinks) { (if (host) localTrack else remoteTrack)?.removeSink(sink); sinks.remove(sink) } }
    internal fun createPreview(context: Context): VideoView? = synchronized(sinks) {
        if (closed.get()) null else VideoView(context, egl.eglBaseContext).also { previewViews.add(it) }
    }
    internal fun releasePreview(view: VideoView) {
        // A failed view release must not let the shared EGL context disappear underneath it.
        if (runCatching { view.release() }.isFailure) return
        val releaseEgl = synchronized(sinks) {
            previewViews.remove(view)
            takeEglRelease()
        }
        if (releaseEgl) runCatching { egl.release() }
    }
    private fun takeEglRelease(): Boolean {
        if (!pipelineClosed || previewViews.isNotEmpty() || eglReleased) return false
        eglReleased = true
        return true
    }
    /** Switch only the existing camera pipeline; the owning session gates recording concurrently. */
    internal fun switchCamera(front: Boolean, complete: (Boolean?, String?) -> Unit): Boolean {
        if (closed.get() || !host) return false
        return runCatching {
            executor.execute {
                try {
                    if (closed.get()) { complete(null, "Kamerasitzung beendet."); return@execute }
                    val current = camera ?: run { complete(null, "Kamera ist noch nicht bereit."); return@execute }
                    val enumerator: CameraEnumerator = if (Camera2Enumerator.isSupported(context)) Camera2Enumerator(context) else Camera1Enumerator(false)
                    val target = enumerator.deviceNames.firstOrNull { if (front) enumerator.isFrontFacing(it) else enumerator.isBackFacing(it) }
                    if (target == null) { complete(null, "Gewählte Kamera ist nicht verfügbar."); return@execute }
                    current.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
                        override fun onCameraSwitchDone(isFrontCamera: Boolean) { complete(isFrontCamera, null) }
                        override fun onCameraSwitchError(errorDescription: String) { complete(null, errorDescription.take(200)) }
                    }, target)
                } catch (_: Exception) { complete(null, "Kamerawechsel fehlgeschlagen.") }
            }
        }.isSuccess
    }

    internal fun withLocalTrack(action: (VideoTrack?, EglBase.Context) -> Unit): Boolean {
        if (closed.get()) return false
        return runCatching {
            executor.execute { action(if (closed.get() || !host) null else localTrack, egl.eglBaseContext) }
        }.isSuccess
    }
    internal fun browserPreviewAvailable(): Boolean = host && !closed.get() && directPath == null && cameraFrameAvailable && browserPreviewSafety.mayStart()

    /** All peer operations run on the camera owner executor; closing this peer never closes the track. */
    internal fun startBrowserPreview(id: String, offer: String, permitted: () -> Boolean, emit: (String, String, String?, Int?) -> Unit) = execute {
        if (!permitted() || !browserPreviewAvailable() || !browserPreviewSafety.mayStart() || browserPreview != null || factory == null || localTrack == null) {
            emit("end", "", null, null)
            return@execute
        }
        browserPreviewId = id
        try {
            val created = BrowserPreviewPeer(context.applicationContext, factory!!, localTrack!!, ::execute) { kind, data, mid, line ->
                if (browserPreviewId == id) {
                    emit(kind, data, mid, line)
                    if (kind == "end") stopBrowserPreview(id)
                }
            }
            browserPreview = created
            created.offer(offer)
        } catch (_: Exception) {
            // A constructor can fail after making a native peer without ever
            // returning an object. Even its own cleanup may have failed.
            // For an existing object, preserve it if its close fails.
            val failedPeer = browserPreview
            val cleanupConfirmed = failedPeer != null && runCatching { failedPeer.close() }.isSuccess
            if (cleanupConfirmed) browserPreview = null else browserPreviewSafety.markCleanupUnknown()
            browserPreviewId = null
            emit("end", "", null, null)
        }
    }
    internal fun browserPreviewCandidate(id: String, data: String, mid: String?, line: Int?) = execute {
        if (browserPreviewId == id) browserPreview?.candidate(data, mid, line)
    }
    /** The callback only confirms that this executor completed the peer close. */
    internal fun stopBrowserPreview(id: String, result: (Boolean) -> Unit = {}) {
        if (closed.get()) { result(false); return }
        val submitted = runCatching {
            executor.execute {
                if (closed.get()) { result(false); return@execute }
                if (!browserPreviewSafety.mayConfirmNoPeer()) { result(false); return@execute }
                if (browserPreviewId != id) {
                    // There is no peer to close if start never created one or it
                    // has already been removed after a successful close.
                    result(browserPreviewId == null && browserPreview == null)
                    return@execute
                }
                val ok = runCatching { browserPreview?.close() }.isSuccess
                if (ok) { browserPreviewId = null; browserPreview = null }
                else browserPreviewSafety.markCleanupUnknown()
                result(ok)
            }
        }.isSuccess
        if (!submitted) result(false)
    }
    internal fun stopAnyBrowserPreview() = execute {
        browserPreviewId = null
        val ok = runCatching { browserPreview?.close() }.isSuccess
        if (ok) browserPreview = null else browserPreviewSafety.markCleanupUnknown()
    }
    private fun execute(block: () -> Unit) { if (!closed.get()) runCatching { executor.execute { if (!closed.get()) block() } } }
    override fun close() {
        synchronized(sinks) { if (!closed.compareAndSet(false, true)) return }
        executor.execute {
            browserPreviewId = null
            if (runCatching { browserPreview?.close() }.isFailure) browserPreviewSafety.markCleanupUnknown()
            browserPreview = null
            val track = synchronized(sinks) {
                sinks.forEach { (if (host) localTrack else remoteTrack)?.removeSink(it) }
                sinks.clear()
                val old = localTrack; localTrack = null; remoteTrack = null; old
            }
            val cameraStopped = runCatching { camera?.stopCapture() }.isSuccess
            val cameraDisposed = runCatching { camera?.dispose() }.isSuccess
            val textureDisposed = runCatching { textureHelper?.dispose() }.isSuccess
            if (host && cameraStopped && cameraDisposed && textureDisposed) cameraLease?.let { releaseCamera(it) }
            runCatching { peer?.close(); peer?.dispose() }; runCatching { track?.dispose() }; runCatching { source?.dispose() }; runCatching { factory?.dispose() }
            val releaseEgl = synchronized(sinks) { pipelineClosed = true; takeEglRelease() }
            if (releaseEgl) runCatching { egl.release() }
        }
        executor.shutdown()
    }
}
private open class SimpleSdpObserver(private val failure: (String) -> Unit) : SdpObserver {
    override fun onCreateSuccess(description: SessionDescription) = Unit
    override fun onSetSuccess() = Unit
    override fun onCreateFailure(error: String) { failure(error) }
    override fun onSetFailure(error: String) { failure(error) }
}
