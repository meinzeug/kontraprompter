package de.kontrakurs.kontraprompter.video

import android.content.Context
import de.kontrakurs.kontraprompter.BuildConfig
import de.kontrakurs.kontraprompter.network.LanSession
import org.json.JSONObject
import org.webrtc.*
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Preview only: owns the camera. It does not start or pretend to start an MP4 recording. */
class RtcVideo(private val context: Context, private val host: Boolean, private val lan: LanSession, private val cameraLease: Long? = null, private val preferFrontCamera: Boolean = true) : AutoCloseable {
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
    private var source: VideoSource? = null
    private val captureProfile = CameraCaptureProfile.select(BuildConfig.DEBUG, BuildConfig.EXPERIMENTAL_RAW_RECORDING, BuildConfig.EXPERIMENTAL_FIXED_CAMERA_FPS)
    private val rawFrames = if (captureProfile.raw) RawFrameFanout<VideoFrame>() else null
    private var localTrack: VideoTrack? = null
    private var camera: CameraVideoCapturer? = null
    private var textureHelper: SurfaceTextureHelper? = null
    private var remoteTrack: VideoTrack? = null
    @Volatile internal var onCameraFailure: ((String) -> Unit)? = null
    @Volatile internal var onCameraSelection: ((Boolean?, Boolean) -> Unit)? = null
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
                factory = PeerConnectionFactory.builder()
                    .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
                    .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext)).createPeerConnectionFactory()
                val config = PeerConnection.RTCConfiguration(emptyList()).apply { sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN }
                peer = factory!!.createPeerConnection(config, observer(generation)) ?: error("PeerConnection nicht verfügbar")
                if (host) {
                    val enumerator: CameraEnumerator = if (Camera2Enumerator.isSupported(context)) Camera2Enumerator(context) else Camera1Enumerator(false)
                    val name = chooseCamera(enumerator.deviceNames.toList(), preferFrontCamera, enumerator::isFrontFacing, enumerator::isBackFacing) ?: error("Keine Kamera verfügbar")
                    onCameraSelection?.invoke(if (enumerator.isFrontFacing(name)) true else if (enumerator.isBackFacing(name)) false else null, enumerator.deviceNames.any { enumerator.isFrontFacing(it) } && enumerator.deviceNames.any { enumerator.isBackFacing(it) })
                    camera = if (captureProfile.fixedFps && enumerator is Camera2Enumerator) {
                        // Isolated diagnostic build only. The selected AE range still requires device verification.
                        KpExperimentalCamera2Capturer(context, name, cameraEvents(), 20)
                    } else enumerator.createCapturer(name, cameraEvents()) ?: error("Kamera konnte nicht geöffnet werden")
                    textureHelper = SurfaceTextureHelper.create("KontraCamera", egl.eglBaseContext) ?: error("Kameraoberfläche nicht verfügbar")
                    source = factory!!.createVideoSource(false)
                    val upstream = source!!.capturerObserver
                    val captureObserver = if (rawFrames == null) upstream else object : CapturerObserver {
                        override fun onCapturerStarted(success: Boolean) = upstream.onCapturerStarted(success)
                        override fun onCapturerStopped() = upstream.onCapturerStopped()
                        override fun onFrameCaptured(frame: VideoFrame) {
                            // The camera owns this borrowed frame for this synchronous call.
                            // Continue to deliver to WebRTC even if the recorder fails.
                            rawFrames.deliverThen(frame, upstream::onFrameCaptured)
                        }
                    }
                    camera!!.initialize(textureHelper, context.applicationContext, captureObserver)
                    val track = factory!!.createVideoTrack("camera", source)
                    synchronized(sinks) { localTrack = track; sinks.forEach { track.addSink(it) } }
                    peer!!.addTrack(track, listOf("kontra")) ?: error("Videospur konnte nicht hinzugefügt werden")
                    preferVp8()
                    camera!!.startCapture(captureProfile.width, captureProfile.height, 20)
                    peer!!.createOffer(createDescriptionObserver(generation), MediaConstraints())
                }
            } catch (e: Exception) { lan.report("Videofehler", e.localizedMessage ?: "Video konnte nicht gestartet werden") }
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
            lan.send("ice", JSONObject().put("mid", c.sdpMid).put("line", c.sdpMLineIndex).put("candidate", c.sdp).toString())
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
            try {
                when (type) {
                    "sdp" -> {
                        val json = JSONObject(value)
                        val kind = when (json.getString("type")) { "offer" -> SessionDescription.Type.OFFER; "answer" -> SessionDescription.Type.ANSWER; else -> error("Ungültiger SDP-Typ") }
                        if ((host && kind != SessionDescription.Type.ANSWER) || (!host && kind != SessionDescription.Type.OFFER)) error("Unerwarteter SDP-Typ")
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
                        if (remoteDescriptionSet) peer?.addIceCandidate(candidate) else if (pendingCandidates.size < 256) pendingCandidates.add(candidate) else error("Zu viele ICE-Kandidaten")
                    }
                }
            } catch (e: Exception) { lan.report("Videofehler", e.localizedMessage ?: "Ungültige Videonachricht") }
        }
    }
    private fun createDescriptionObserver(generation: Long) = object : SimpleSdpObserver({ error -> reportSdpFailure(generation, error) }) {
        override fun onCreateSuccess(description: SessionDescription) = executePrimary(generation) {
            peer?.setLocalDescription(object : SimpleSdpObserver({ error -> reportSdpFailure(generation, error) }) {
                override fun onSetSuccess() = executePrimary(generation) {
                    lan.send("sdp", JSONObject().put("type", description.type.canonicalForm()).put("sdp", description.description).toString())
                }
            }, description)
        }
    }
    private fun reportSdpFailure(generation: Long, error: String) = executePrimary(generation) { lan.report("Videofehler", error) }
    private fun executePrimary(generation: Long, action: () -> Unit) = execute {
        if (primaryGeneration.isCurrent(generation)) action()
    }
    internal fun invalidatePrimaryPeer() = primaryGeneration.invalidate()
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

    internal fun withRecordingSources(action: (VideoTrack?, RawFrameSource<VideoFrame>?, EglBase.Context) -> Unit): Boolean {
        if (closed.get()) return false
        return runCatching {
            executor.execute { val track = if (closed.get() || !host) null else localTrack; action(track, if (track == null) null else rawFrames, egl.eglBaseContext) }
        }.isSuccess
    }
    internal fun browserPreviewAvailable(): Boolean = host && !closed.get() && cameraFrameAvailable && browserPreviewSafety.mayStart()

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
            rawFrames?.close()
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
