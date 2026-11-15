package de.kontrakurs.kontraprompter.video

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import org.webrtc.EglBase
import org.webrtc.EglRenderer
import org.webrtc.GlRectDrawer
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import org.webrtc.VideoTrack
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.max

/**
 * Isolated recorder for an already running local WebRTC VideoTrack. The caller owns
 * camera permission, RECORD_AUDIO permission, the capture track and its lifecycle.
 * This class never opens or closes the camera or PeerConnection. A caller must keep
 * the track alive when the LAN peer disconnects and while this recorder finalizes.
 *
 * No UI/API/foreground-service integration is performed by this class. Files and
 * manifests are private to the app, never overwritten or deleted on failure.
 */
internal class RtcMp4Recorder(
    context: Context,
    private val track: VideoTrack,
    private val sharedEglContext: EglBase.Context,
    private val recordingId: String,
    private val projectId: String?,
    private val origin: RecordingOrigin,
    private val listener: (Event) -> Unit,
) : VideoSink, Closeable, RecordingControl {
    sealed interface Event {
        data class Preparing(val recordingId: String) : Event
        data class Recording(val recordingId: String, val width: Int, val height: Int) : Event
        data class Finalizing(val recordingId: String, val reason: StopReason) : Event
        data class Ready(val recordingId: String, val projectId: String?, val file: File, val bytes: Long, val durationUs: Long, val sha256: String, val reason: StopReason) : Event
        data class Partial(val recordingId: String, val file: File, val bytes: Long, val durationUs: Long, val message: String) : Event
        data class Failed(val recordingId: String, val message: String, val retainedFile: File?, val blocked: Boolean = false) : Event
        data class ResourcesReleased(val recordingId: String) : Event
    }
    enum class StopReason { USER, STORAGE, SIZE_LIMIT, DURATION_LIMIT, ERROR }

    companion object {
        private const val SAMPLE_RATE = 48_000
        private const val AUDIO_BITRATE = 96_000
        private const val VIDEO_BITRATE = 1_800_000
        private const val VIDEO_FPS = 20
        private const val MAX_DURATION_MS = 30 * 60 * 1000L
        private const val START_TIMEOUT_MS = 12_000L
        private const val DRAIN_TIMEOUT_MS = 12_000L
        private const val AUDIO_CLOCK_TIMEOUT_MS = 5_000L
        private const val MAX_CLOCK_DRIFT_US = 250_000L
        private const val DATA_STALL_MS = 4_000L
        private val resourceGate = RecordingResourceGate()
        // Fixture branch only: report the real recorder's resource release to the test.
        internal fun fixtureRecorderAvailable(): Boolean = resourceGate.isAvailable()
    }

    private val app = context.applicationContext
    private val callback = Handler(Looper.getMainLooper())
    private val control = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "KontraRecorderControl") }
    private val watchdog = Executors.newSingleThreadScheduledExecutor { runnable -> Thread(runnable, "KontraRecorderWatchdog") }
    private val started = AtomicBoolean(false)
    private var resourceLease: Long? = null
    private val stopRequested = AtomicBoolean(false)
    private val pauseLock = Any()
    private val configuring = AtomicBoolean(false)
    private val configured = AtomicBoolean(false)
    private val firstMedia = AtomicBoolean(false)
    private val finished = AtomicBoolean(false)
    private val failure = AtomicReference<String?>(null)
    private val reason = AtomicReference(StopReason.USER)
    private val lastVideoNs = AtomicLong(-1)
    private val lastVideoAtMs = AtomicLong(0)
    private val lastAudioAtMs = AtomicLong(0)
    private val lastEncodedVideoMs = AtomicLong(0)
    private val lastEncodedAudioMs = AtomicLong(0)
    @Volatile private var archive: RecordingArchive? = null
    @Volatile private var file: RandomAccessFile? = null
    @Volatile private var mux: RecordingMuxer? = null
    @Volatile private var videoEncoder: MediaCodec? = null
    @Volatile private var audioEncoder: MediaCodec? = null
    @Volatile private var audioInput: AudioRecord? = null
    @Volatile private var encoderSurface: Surface? = null
    @Volatile private var renderer: EglRenderer? = null
    @Volatile private var timeline: RecordingTimeline? = null
    @Volatile private var pauseTimeline: RecordingPauseTimeline? = null
    @Volatile private var timelineOriginNs = 0L
    @Volatile private var cameraOffsetNs: Long? = null
    @Volatile private var width = 0
    @Volatile private var height = 0
    @Volatile private var attached = false
    @Volatile private var pipelineAtMs = 0L
    @Volatile private var startAtMs = 0L
    private var audioFeed: Thread? = null
    private var audioDrain: Thread? = null
    private var videoDrain: Thread? = null

    fun start(): Boolean {
        if (!started.compareAndSet(false, true)) return false
        resourceLease = resourceGate.tryAcquire() ?: return false
        control.execute {
            try {
                check(app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) { "Mikrofonberechtigung fehlt" }
                val reserved = RecordingArchive.reserve(app, recordingId, projectId, origin)
                archive = reserved
                startAtMs = SystemClock.elapsedRealtime()
                emit(Event.Preparing(reserved.recordingId))
                if (!stopRequested.get()) { track.addSink(this); attached = true }
                watchdog.scheduleAtFixedRate({ checkLimits() }, 1, 1, TimeUnit.SECONDS)
            } catch (e: Exception) { fail(safeMessage(e, "Aufnahme konnte nicht vorbereitet werden")) }
        }
        return true
    }

    override fun stop(): Boolean {
        if (!started.get() || finished.get()) return false
        requestStop(StopReason.USER)
        return true
    }

    /** Stop accepting new camera/audio samples without closing the MP4. */
    override fun pause(): Boolean = synchronized(pauseLock) {
        if (!configured.get() || !firstMedia.get() || stopRequested.get() || finished.get()) return false
        pauseTimeline?.pause(System.nanoTime()) == true
    }

    override fun resume(): Boolean = synchronized(pauseLock) {
        if (!configured.get() || stopRequested.get() || finished.get() || pauseTimeline?.isPaused() != true) return false
        val encoder = videoEncoder ?: return false
        // A failed request leaves the recording paused; the encoder may still emit the next periodic keyframe.
        if (runCatching { encoder.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) }.isFailure) return false
        // Reset while still paused so the watchdog cannot observe stale stream timestamps.
        val now = SystemClock.elapsedRealtime()
        lastVideoAtMs.set(now); lastAudioAtMs.set(now)
        lastEncodedVideoMs.set(now); lastEncodedAudioMs.set(now)
        pauseTimeline?.resume(System.nanoTime()) == true
    }

    override fun close() { stop() }

    override fun abort(message: String) { if (started.get() && !finished.get()) fail(message) }

    override fun onFrame(frame: VideoFrame) {
        if (!started.get() || stopRequested.get() || finished.get()) return
        if (!configured.get()) {
            if (configuring.compareAndSet(false, true)) {
                val w = frame.rotatedWidth; val h = frame.rotatedHeight
                val rawTimestamp = frame.timestampNs
                if (rawTimestamp <= 0) { fail("Kamerazeitstempel nicht verfügbar"); return }
                val offset = try { Math.subtractExact(System.nanoTime(), rawTimestamp) } catch (_: ArithmeticException) { fail("Kamerazeitstempel ungültig"); return }
                cameraOffsetNs = offset
                control.execute { configure(w, h) }
            }
            return
        }
        lastVideoAtMs.set(SystemClock.elapsedRealtime())
        if (frame.rotatedWidth != width || frame.rotatedHeight != height) { fail("Kameraausrichtung hat sich während der Aufnahme geändert"); return }
        val now = System.nanoTime()
        val offset = cameraOffsetNs ?: return
        val absoluteNs = try { Math.addExact(frame.timestampNs, offset) } catch (_: ArithmeticException) { fail("Kamerazeitstempel ungültig"); return }
        val deliveryDelay = try { Math.subtractExact(now, absoluteNs) } catch (_: ArithmeticException) { fail("Kamerazeitstempel ungültig"); return }
        if (frame.timestampNs <= 0 || deliveryDelay < -100_000_000L || deliveryDelay > 500_000_000L) { fail("Zeitbasis der Kamera ist nicht stabil"); return }
        val previous = lastVideoNs.getAndSet(absoluteNs)
        if (previous >= absoluteNs) return // A duplicate frame must not corrupt encoder timestamps.
        val relative = pauseTimeline?.project(absoluteNs) ?: return
        val target = renderer ?: return
        frame.buffer.retain()
        val adjusted = VideoFrame(frame.buffer, frame.rotation, relative)
        try { target.onFrame(adjusted) } catch (_: Exception) { fail("Videobild konnte nicht aufgezeichnet werden") }
        finally { adjusted.release() }
    }

    private fun configure(w: Int, h: Int) {
        if (stopRequested.get()) return
        try {
            require(w > 0 && h > 0 && w % 2 == 0 && h % 2 == 0 && w <= 4096 && h <= 4096) { "Kameraauflösung wird nicht unterstützt" }
            val selected = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { info ->
                if (!info.isEncoder || info.supportedTypes.none { it.equals("video/avc", true) }) false
                else runCatching {
                    val caps = info.getCapabilitiesForType("video/avc")
                    caps.colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface) && caps.videoCapabilities.areSizeAndRateSupported(w, h, VIDEO_FPS.toDouble())
                }.getOrDefault(false)
            } ?: error("Kein passender H.264-Oberflächenencoder für diese Kameraauflösung")
            val videoCaps = selected.getCapabilitiesForType("video/avc").videoCapabilities
            val videoFormat = MediaFormat.createVideoFormat("video/avc", w, h).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, VIDEO_BITRATE.coerceIn(videoCaps.bitrateRange.lower, videoCaps.bitrateRange.upper))
                setInteger(MediaFormat.KEY_FRAME_RATE, VIDEO_FPS)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
                if (Build.VERSION.SDK_INT >= 29) setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            }
            val video = MediaCodec.createByCodecName(selected.name).also { videoEncoder = it }
            video.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = video.createInputSurface().also { encoderSurface = it }
            video.start()

            val audioFormat = MediaFormat.createAudioFormat("audio/mp4a-latm", SAMPLE_RATE, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BITRATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 8192)
            }
            val audio = MediaCodec.createEncoderByType("audio/mp4a-latm").also { audioEncoder = it }
            audio.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); audio.start()
            val minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(minimum > 0) { "Mikrofonformat wird nicht unterstützt" }
            val recorder = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(max(minimum * 2, SAMPLE_RATE / 2 * 2)).build().also { audioInput = it }
            check(recorder.state == AudioRecord.STATE_INITIALIZED) { "Mikrofon konnte nicht geöffnet werden" }
            val targetFile = RandomAccessFile(archive!!.mediaFile, "rw").also { file = it }
            val targetMux = RecordingMuxer(targetFile.fd, {
                if (firstMedia.compareAndSet(false, true)) control.execute {
                    if (stopRequested.get() || finished.get()) return@execute
                    try { archive?.write("recording"); emit(Event.Recording(archive!!.recordingId, width, height)) }
                    catch (_: Exception) { fail("Aufnahmestatus konnte nicht gespeichert werden") }
                }
            }, { requestStop(StopReason.SIZE_LIMIT) }).also { mux = it }
            val egl = EglRenderer("KontraRecorder").also { renderer = it }
            egl.setErrorCallback { fail("Videospeicher erschöpft") }
            egl.init(sharedEglContext, EglBase.CONFIG_RECORDABLE, GlRectDrawer(), true)
            egl.createEglSurface(surface)
            width = w; height = h
            val originNs = System.nanoTime()
            timelineOriginNs = originNs
            timeline = RecordingTimeline(originNs)
            pauseTimeline = RecordingPauseTimeline(originNs)
            recorder.startRecording()
            check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Mikrofonaufnahme konnte nicht gestartet werden" }
            pipelineAtMs = SystemClock.elapsedRealtime()
            videoDrain = Thread({ drain(video, RecordingMuxer.VIDEO, targetMux) }, "KontraVideoDrain").also { it.start() }
            audioDrain = Thread({ drain(audio, RecordingMuxer.AUDIO, targetMux) }, "KontraAudioDrain").also { it.start() }
            audioFeed = Thread({ feedAudio(recorder, audio) }, "KontraAudioInput").also { it.start() }
            configured.set(true)
        } catch (e: Exception) { fail(safeMessage(e, "Encoder oder Mikrofon konnte nicht gestartet werden")) }
    }

    private fun feedAudio(recorder: AudioRecord, codec: MediaCodec) {
        var framesRead = 0L; var firstFrame = -1L; var firstPts = -1L; var lastRawPts = 0L; var lastPts = 0L
        var timestampUnavailableSince = SystemClock.elapsedRealtime()
        var lastSilenceCheck = 0L
        val bytes = ByteArray(2048); val stamp = AudioTimestamp()
        try {
            while (!stopRequested.get()) {
                val nowMs = SystemClock.elapsedRealtime()
                if (Build.VERSION.SDK_INT >= 29 && nowMs - lastSilenceCheck >= 500) {
                    lastSilenceCheck = nowMs
                    if (recorder.activeRecordingConfiguration?.isClientSilenced == true) error("Mikrofon wird vom System stummgeschaltet")
                }
                val n = recorder.read(bytes, 0, bytes.size, AudioRecord.READ_BLOCKING)
                if (n <= 0) { if (stopRequested.get()) break else error("Mikrofon konnte keine Daten lesen") }
                check(n % 2 == 0) { "Ungültige Mikrofondaten" }
                val firstRead = framesRead; framesRead += n / 2
                val clockAvailable = recorder.getTimestamp(stamp, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS && stamp.nanoTime > 0
                if (!clockAvailable) {
                    if (SystemClock.elapsedRealtime() - timestampUnavailableSince > AUDIO_CLOCK_TIMEOUT_MS) error("Mikrofonzeitstempel nicht verfügbar")
                    if (firstFrame < 0) continue
                } else timestampUnavailableSince = SystemClock.elapsedRealtime()
                val measured = if (clockAvailable) timeline!!.audioTimestampUs(stamp.nanoTime, stamp.framePosition, firstRead, SAMPLE_RATE) else null
                if (firstFrame < 0) {
                    if (measured == null) continue
                    firstFrame = firstRead; firstPts = measured
                }
                val rawPts = firstPts + (firstRead - firstFrame) * 1_000_000L / SAMPLE_RATE
                if (measured != null && abs(measured - rawPts) > MAX_CLOCK_DRIFT_US) error("Mikrofonzeitbasis ist instabil")
                check(rawPts > lastRawPts || firstRead == firstFrame) { "Mikrofonzeit läuft rückwärts" }
                lastRawPts = rawPts
                val absoluteNs = Math.addExact(timelineOriginNs, Math.multiplyExact(rawPts, 1_000L))
                val spanNs = (n / 2).toLong() * 1_000_000_000L / SAMPLE_RATE
                val pts = (pauseTimeline?.project(absoluteNs, spanNs) ?: continue) / 1_000L
                check(pts > lastPts || firstRead == firstFrame) { "Aufnahmezeit läuft rückwärts" }
                val index = obtainInput(codec, 2_000)
                val input = codec.getInputBuffer(index) ?: error("Audiopuffer nicht verfügbar")
                input.clear(); check(input.remaining() >= n) { "Audiopuffer zu klein" }; input.put(bytes, 0, n)
                codec.queueInputBuffer(index, 0, n, pts, 0); lastPts = pts
                lastAudioAtMs.set(SystemClock.elapsedRealtime())
            }
            val eosIndex = obtainInput(codec, 3_000)
            codec.queueInputBuffer(eosIndex, 0, 0, lastPts + 1, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        } catch (e: Exception) {
            if (!stopRequested.get()) fail(safeMessage(e, "Mikrofonaufnahme unterbrochen"))
            else if (failure.get() == null) failure.compareAndSet(null, "Tonspur konnte nicht vollständig abgeschlossen werden")
        }
    }

    private fun obtainInput(codec: MediaCodec, timeoutMs: Long): Int {
        val until = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < until) {
            val index = codec.dequeueInputBuffer(10_000)
            if (index >= 0) return index
        }
        error("Audioencoder reagiert nicht")
    }

    private fun drain(codec: MediaCodec, trackId: Int, target: RecordingMuxer) {
        val info = MediaCodec.BufferInfo(); var stoppedAt = 0L
        try {
            while (true) {
                if (stopRequested.get()) {
                    if (stoppedAt == 0L) stoppedAt = SystemClock.elapsedRealtime()
                    if (SystemClock.elapsedRealtime() - stoppedAt > DRAIN_TIMEOUT_MS) error("Encoder konnte nicht abgeschlossen werden")
                }
                when (val index = codec.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> target.addFormat(trackId, codec.outputFormat)
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> if (index >= 0) {
                        val end = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        try {
                            val buffer = codec.getOutputBuffer(index) ?: error("Encoderpuffer nicht verfügbar")
                            if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                (if (trackId == RecordingMuxer.VIDEO) lastEncodedVideoMs else lastEncodedAudioMs).set(SystemClock.elapsedRealtime())
                            }
                            target.addSample(trackId, buffer, info)
                        } finally { codec.releaseOutputBuffer(index, false) }
                        if (end) break
                    }
                }
            }
        } catch (e: Exception) { fail(safeMessage(e, "Encoderfehler")) }
    }

    private fun checkLimits() {
        if (finished.get() || stopRequested.get()) return
        try {
            val now = SystemClock.elapsedRealtime()
            if ((!configured.get() && now - startAtMs > START_TIMEOUT_MS) || (configured.get() && !firstMedia.get() && now - pipelineAtMs > START_TIMEOUT_MS)) { fail("Kamera oder Mikrofon liefert keine aufnehmbaren Daten"); return }
            synchronized(pauseLock) {
              if (firstMedia.get() && pauseTimeline?.isPaused() != true) {
                val videoAt = lastVideoAtMs.get(); val audioAt = lastAudioAtMs.get()
                val encodedVideoAt = lastEncodedVideoMs.get(); val encodedAudioAt = lastEncodedAudioMs.get()
                fun stalled(last: Long) = if (last == 0L) now - pipelineAtMs > DATA_STALL_MS else now - last > DATA_STALL_MS
                if (stalled(videoAt)) { fail("Kamera liefert keine neuen Bilder"); return }
                if (stalled(audioAt)) { fail("Mikrofon liefert keine neuen Daten"); return }
                if (stalled(encodedVideoAt)) { fail("Videoencoder liefert keine neuen Daten"); return }
                if (stalled(encodedAudioAt)) { fail("Audioencoder liefert keine neuen Daten"); return }
              }
            }
            if (configured.get() && now - pipelineAtMs >= MAX_DURATION_MS) { requestStop(StopReason.DURATION_LIMIT); return }
            if ((archive?.availableBytes() ?: 0) < RecordingArchive.MIN_FREE_BYTES) requestStop(StopReason.STORAGE)
        } catch (_: Exception) { fail("Speicherstatus konnte nicht geprüft werden") }
    }

    private fun fail(message: String) {
        failure.compareAndSet(null, message.take(240))
        requestStop(StopReason.ERROR)
    }
    private fun requestStop(value: StopReason) {
        synchronized(pauseLock) {
            if (value == StopReason.ERROR) reason.set(value)
            if (!stopRequested.compareAndSet(false, true)) return
            if (value != StopReason.ERROR) reason.compareAndSet(StopReason.USER, value)
        }
        control.execute { finalizeRecording() }
    }

    private fun finalizeRecording() {
        if (finished.get()) return
        val current = archive
        var unsafeRelease = false
        var containerFinalized = false
        if (current != null) {
            runCatching { current.write("finalizing", stopReason = reason.get().name.lowercase()) }.onFailure { failure.compareAndSet(null, "Aufnahmestatus konnte nicht gespeichert werden") }
            emit(Event.Finalizing(current.recordingId, reason.get()))
        }
        watchdog.shutdownNow()
        if (attached) { runCatching { track.removeSink(this) }.onFailure { unsafeRelease = true; failure.compareAndSet(null, "Videospur konnte nicht gelöst werden") }; attached = false }
        runCatching { renderer?.release() }.onFailure { unsafeRelease = true; failure.compareAndSet(null, "Videooberfläche konnte nicht geschlossen werden") }; renderer = null
        runCatching { videoEncoder?.signalEndOfInputStream() }.onFailure { if (videoEncoder != null) failure.compareAndSet(null, "Videoencoder konnte nicht abgeschlossen werden") }
        runCatching { audioInput?.stop() }.onFailure { if (audioInput != null && configured.get()) failure.compareAndSet(null, "Mikrofon konnte nicht gestoppt werden") }
        val workers = listOfNotNull(audioFeed, audioDrain, videoDrain)
        for (thread in workers) {
            try { thread.join(DRAIN_TIMEOUT_MS) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); failure.compareAndSet(null, "Aufnahmeabschluss wurde unterbrochen") }
            if (thread.isAlive) failure.compareAndSet(null, "Encoder konnte nicht rechtzeitig beendet werden")
        }
        runCatching { audioInput?.release() }.onFailure { unsafeRelease = true; failure.compareAndSet(null, "Mikrofon konnte nicht freigegeben werden") }; audioInput = null
        runCatching { audioEncoder?.stop() }.onFailure { if (audioEncoder != null) failure.compareAndSet(null, "Audioencoder konnte nicht geschlossen werden") }
        runCatching { audioEncoder?.release() }.onFailure { unsafeRelease = true; failure.compareAndSet(null, "Audioencoder konnte nicht freigegeben werden") }; audioEncoder = null
        synchronized(pauseLock) {
            runCatching { videoEncoder?.stop() }.onFailure { if (videoEncoder != null) failure.compareAndSet(null, "Videoencoder konnte nicht geschlossen werden") }
            runCatching { videoEncoder?.release() }.onFailure { unsafeRelease = true; failure.compareAndSet(null, "Videoencoder konnte nicht freigegeben werden") }; videoEncoder = null
        }
        runCatching { encoderSurface?.release() }.onFailure { unsafeRelease = true; failure.compareAndSet(null, "Videooberfläche konnte nicht freigegeben werden") }; encoderSurface = null
        if (workers.any { it.isAlive }) {
            for (thread in workers) runCatching { thread.join(1_000) }
        }
        if (workers.any { it.isAlive }) {
            val message = "Encoder ist noch aktiv. Aufnahme bleibt gesperrt, bis Ressourcen frei sind."
            failure.compareAndSet(null, message)
            runCatching { current?.write("error", error = message, stopReason = "error") }
            finished.set(true)
            emit(Event.Failed(current?.recordingId ?: recordingId, message, current?.mediaFile?.takeIf { it.exists() }, blocked = true))
            val canReleaseGate = !unsafeRelease
            Thread({
                awaitWorkersBeforeCleanup(workers, cleanup = {
                    runCatching { mux?.abandon(); file?.fd?.sync(); file?.close() }.isSuccess && canReleaseGate
                }, released = {
                    resourceGate.release(checkNotNull(resourceLease)); emit(Event.ResourcesReleased(recordingId))
                })
            }, "KontraRecorderLateCleanup").apply { isDaemon = true; start() }
            control.shutdown()
            return
        }
        val activeMux = mux
        try { activeMux?.finish() ?: error("Video- oder Tonspur wurde nicht gestartet"); containerFinalized = true }
        catch (e: Exception) { failure.compareAndSet(null, safeMessage(e, "MP4 konnte nicht abgeschlossen werden")) }
        if (activeMux != null && !activeMux.isReleased()) unsafeRelease = true
        mux = null
        runCatching { file?.fd?.sync() }.onFailure { failure.compareAndSet(null, "Aufnahmedatei konnte nicht vollständig gespeichert werden") }
        runCatching { file?.close() }.onFailure { unsafeRelease = true; failure.compareAndSet(null, "Aufnahmedatei konnte nicht geschlossen werden") }; file = null
        if (!unsafeRelease) resourceGate.release(checkNotNull(resourceLease))
        if (failure.get() == null && current != null) {
            try {
                val verified = RecordingVerifier.verify(current.mediaFile)
                current.write("ready", bytes = verified.bytes, durationUs = verified.durationUs, sha256 = verified.sha256, stopReason = reason.get().name.lowercase())
                finished.set(true)
                emit(Event.Ready(current.recordingId, current.projectId, current.mediaFile, verified.bytes, verified.durationUs, verified.sha256, reason.get()))
            } catch (e: Exception) { failure.compareAndSet(null, safeMessage(e, "Aufnahmeprüfung fehlgeschlagen")) }
        }
        if (!finished.get()) {
            val message = failure.get() ?: "Aufnahme wurde nicht fertiggestellt"
            val recovered = if (containerFinalized && !unsafeRelease && current != null) runCatching { RecordingVerifier.verify(current.mediaFile, partial = true) }.getOrNull() else null
            if (recovered != null && current != null && runCatching { current.write("partial", error = message, bytes = recovered.bytes, durationUs = recovered.durationUs, sha256 = recovered.sha256, stopReason = "error") }.isSuccess) {
                finished.set(true)
                emit(Event.Partial(current.recordingId, current.mediaFile, recovered.bytes, recovered.durationUs, message))
            } else {
                runCatching { current?.write("error", error = message, stopReason = reason.get().name.lowercase()) }
                finished.set(true)
                emit(Event.Failed(current?.recordingId ?: recordingId, message, current?.mediaFile?.takeIf { it.exists() }, blocked = unsafeRelease))
            }
        }
        control.shutdown()
    }

    private fun emit(event: Event) { callback.post { runCatching { listener(event) } } }
    private fun safeMessage(error: Exception, fallback: String): String = error.message?.takeIf { it.isNotBlank() }?.take(240) ?: fallback
}
