package de.kontrakurs.kontraprompter.video

import android.content.Context
import de.kontrakurs.kontraprompter.network.LanSession
import org.json.JSONObject
import org.webrtc.*
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Preview only: owns the camera. It does not start or pretend to start an MP4 recording. */
class RtcVideo(private val context: Context, private val host: Boolean, private val lan: LanSession) : AutoCloseable {
    companion object {
        @Volatile private var initialized = false
        @Synchronized private fun initialize(context: Context) {
            if (!initialized) { PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context.applicationContext).createInitializationOptions()); initialized = true }
        }
    }
    val egl: EglBase = EglBase.create()
    private val executor = Executors.newSingleThreadExecutor()
    private val closed = AtomicBoolean(false)
    private var factory: PeerConnectionFactory? = null
    private var peer: PeerConnection? = null
    private var source: VideoSource? = null
    private var localTrack: VideoTrack? = null
    private var camera: CameraVideoCapturer? = null
    private var textureHelper: SurfaceTextureHelper? = null
    private var remoteTrack: VideoTrack? = null
    private val sinks = mutableSetOf<VideoSink>()
    private val pendingCandidates = mutableListOf<IceCandidate>()
    private var remoteDescriptionSet = false

    fun start() = execute {
        try {
            initialize(context)
            factory = PeerConnectionFactory.builder()
                .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
                .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext)).createPeerConnectionFactory()
            val config = PeerConnection.RTCConfiguration(emptyList()).apply { sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN }
            peer = factory!!.createPeerConnection(config, observer()) ?: error("PeerConnection nicht verfügbar")
            if (host) {
                val enumerator: CameraEnumerator = if (Camera2Enumerator.isSupported(context)) Camera2Enumerator(context) else Camera1Enumerator(false)
                val name = enumerator.deviceNames.firstOrNull { enumerator.isBackFacing(it) } ?: enumerator.deviceNames.firstOrNull() ?: error("Keine Kamera verfügbar")
                camera = enumerator.createCapturer(name, cameraEvents()) ?: error("Kamera konnte nicht geöffnet werden")
                textureHelper = SurfaceTextureHelper.create("KontraCamera", egl.eglBaseContext) ?: error("Kameraoberfläche nicht verfügbar")
                source = factory!!.createVideoSource(false)
                camera!!.initialize(textureHelper, context.applicationContext, source!!.capturerObserver)
                val track = factory!!.createVideoTrack("camera", source)
                synchronized(sinks) { localTrack = track; sinks.forEach { track.addSink(it) } }
                peer!!.addTrack(track, listOf("kontra")) ?: error("Videospur konnte nicht hinzugefügt werden")
                preferVp8()
                camera!!.startCapture(640, 480, 20)
                peer!!.createOffer(createDescriptionObserver(), MediaConstraints())
            }
        } catch (e: Exception) { lan.report("Videofehler", e.localizedMessage ?: "Video konnte nicht gestartet werden") }
    }
    private fun preferVp8() {
        val p = peer ?: return
        val caps = factory?.getRtpSenderCapabilities(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO)?.codecs ?: return
        val vp8 = caps.filter { it.name.equals("VP8", true) }
        if (vp8.isNotEmpty()) p.transceivers.firstOrNull { it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO }?.setCodecPreferences(vp8 + caps.filterNot { it.name.equals("VP8", true) })
    }
    private fun cameraEvents() = object : CameraVideoCapturer.CameraEventsHandler {
        override fun onCameraError(error: String) { lan.report("Kamerafehler", error) }
        override fun onCameraDisconnected() { lan.report("Kamerafehler", "Kamera wurde getrennt") }
        override fun onCameraFreezed(error: String) { lan.report("Kamerafehler", error) }
        override fun onCameraOpening(name: String) = Unit
        override fun onFirstFrameAvailable() { lan.reportFirstCameraFrame() }
        override fun onCameraClosed() = Unit
    }
    private fun observer() = object : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
            when (state) {
                PeerConnection.IceConnectionState.CONNECTED, PeerConnection.IceConnectionState.COMPLETED -> lan.report("Videoverbindung hergestellt")
                PeerConnection.IceConnectionState.FAILED -> lan.report("Videoverbindung fehlgeschlagen", "Gleiches lokales Netz prüfen; Hotspot oder WLAN kann Geräte isolieren.")
                PeerConnection.IceConnectionState.DISCONNECTED -> lan.report("Videoverbindung unterbrochen", "Lokales Netz prüfen")
                else -> Unit
            }
        }
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
        override fun onIceCandidate(c: IceCandidate) { lan.send("ice", JSONObject().put("mid", c.sdpMid).put("line", c.sdpMLineIndex).put("candidate", c.sdp).toString()) }
        override fun onIceCandidatesRemoved(c: Array<out IceCandidate>) = Unit
        override fun onAddStream(stream: MediaStream) = Unit
        override fun onRemoveStream(stream: MediaStream) = Unit
        override fun onDataChannel(channel: DataChannel) = Unit
        override fun onRenegotiationNeeded() = Unit
        override fun onTrack(transceiver: RtpTransceiver) { (transceiver.receiver.track() as? VideoTrack)?.let { track -> execute { setRemoteTrack(track) } } }
    }
    private fun setRemoteTrack(track: VideoTrack) {
        synchronized(sinks) {
            if (remoteTrack === track) return
            sinks.forEach { remoteTrack?.removeSink(it) }
            remoteTrack = track
            sinks.forEach { track.addSink(it) }
        }
    }
    fun message(type: String, value: String) = execute {
        try {
            when (type) {
                "sdp" -> {
                    val json = JSONObject(value)
                    val kind = when (json.getString("type")) { "offer" -> SessionDescription.Type.OFFER; "answer" -> SessionDescription.Type.ANSWER; else -> error("Ungültiger SDP-Typ") }
                    if ((host && kind != SessionDescription.Type.ANSWER) || (!host && kind != SessionDescription.Type.OFFER)) error("Unerwarteter SDP-Typ")
                    peer?.setRemoteDescription(object : SimpleSdpObserver(lan) {
                        override fun onSetSuccess() = execute {
                            remoteDescriptionSet = true
                            pendingCandidates.forEach { peer?.addIceCandidate(it) }; pendingCandidates.clear()
                            if (kind == SessionDescription.Type.OFFER) peer?.createAnswer(createDescriptionObserver(), MediaConstraints())
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
    private fun createDescriptionObserver() = object : SimpleSdpObserver(lan) {
        override fun onCreateSuccess(description: SessionDescription) = execute {
            peer?.setLocalDescription(object : SimpleSdpObserver(lan) {
                override fun onSetSuccess() { lan.send("sdp", JSONObject().put("type", description.type.canonicalForm()).put("sdp", description.description).toString()) }
            }, description)
        }
    }
    fun attach(sink: VideoSink) { synchronized(sinks) { if (!closed.get() && sinks.add(sink)) (if (host) localTrack else remoteTrack)?.addSink(sink) } }
    fun detach(sink: VideoSink) { synchronized(sinks) { (if (host) localTrack else remoteTrack)?.removeSink(sink); sinks.remove(sink) } }
    private fun execute(block: () -> Unit) { if (!closed.get()) runCatching { executor.execute { if (!closed.get()) block() } } }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        executor.execute {
            val track = synchronized(sinks) {
                sinks.forEach { (if (host) localTrack else remoteTrack)?.removeSink(it) }
                sinks.clear()
                val old = localTrack; localTrack = null; remoteTrack = null; old
            }
            runCatching { camera?.stopCapture() }; runCatching { camera?.dispose() }; runCatching { textureHelper?.dispose() }
            runCatching { peer?.close(); peer?.dispose() }; runCatching { track?.dispose() }; runCatching { source?.dispose() }; runCatching { factory?.dispose() }
            // Views must be detached before this owner is closed.
            runCatching { egl.release() }
        }
        executor.shutdown()
    }
}
private open class SimpleSdpObserver(private val lan: LanSession) : SdpObserver {
    override fun onCreateSuccess(description: SessionDescription) = Unit
    override fun onSetSuccess() = Unit
    override fun onCreateFailure(error: String) { lan.report("Videofehler", error) }
    override fun onSetFailure(error: String) { lan.report("Videofehler", error) }
}
