package de.kontrakurs.kontraprompter.video

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.FileDescriptor
import java.nio.ByteBuffer

/** The encoders may deliver formats/samples in either order. All muxer calls are serialized. */
internal class RecordingMuxer(fd: FileDescriptor, private val onMediaStarted: () -> Unit, private val onLimit: () -> Unit) {
    companion object {
        const val VIDEO = 0
        const val AUDIO = 1
        // Keep room for MP4 container overhead and stay below the server's 1 GiB limit.
        const val MAX_MEDIA_BYTES = 900L * 1024 * 1024
        private const val MAX_PENDING_BYTES = 8L * 1024 * 1024
    }
    private data class Pending(val track: Int, val bytes: ByteArray, val ptsUs: Long, val flags: Int)
    private val muxer = MediaMuxer(fd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private val tracks = intArrayOf(-1, -1)
    private val ledger = RecordingSampleLedger(MAX_MEDIA_BYTES)
    private val pending = ArrayDeque<Pending>()
    private var pendingBytes = 0L
    private var started = false
    private var closed = false
    private var released = false
    private var notified = false
    private var limitNotified = false

    @Synchronized fun addFormat(track: Int, format: MediaFormat) {
        check(!closed && track in 0..1 && tracks[track] == -1) { "Encoderformat doppelt oder ungültig" }
        tracks[track] = muxer.addTrack(format)
        if (tracks.all { it >= 0 }) {
            muxer.start()
            started = true
            while (pending.isNotEmpty()) write(pending.removeFirst())
            pendingBytes = 0
            maybeNotify()
        }
    }

    @Synchronized fun addSample(track: Int, encoded: ByteBuffer, info: MediaCodec.BufferInfo) {
        check(!closed && track in 0..1)
        if (info.size <= 0 || info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) return
        check(info.offset >= 0 && info.size > 0 && info.offset.toLong() + info.size <= encoded.capacity()) { "Ungültiger Encoderpuffer" }
        check(info.size <= MAX_PENDING_BYTES) { "Encoderpuffer ist zu groß" }
        if (info.size.toLong() > MAX_MEDIA_BYTES - ledger.bytes) {
            if (!limitNotified) { limitNotified = true; onLimit() }
            return
        }
        ledger.add(track, info.presentationTimeUs, info.size)
        val view = encoded.duplicate().apply { clear() }
        view.position(info.offset); view.limit(info.offset + info.size)
        val bytes = ByteArray(info.size); view.get(bytes)
        val sample = Pending(track, bytes, info.presentationTimeUs, info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM.inv())
        if (!started) {
            check(pendingBytes + bytes.size <= MAX_PENDING_BYTES) { "Encoderformate nicht rechtzeitig verfügbar" }
            pending.addLast(sample); pendingBytes += bytes.size
        } else write(sample)
        maybeNotify()
    }

    private fun write(sample: Pending) {
        val info = MediaCodec.BufferInfo().apply { set(0, sample.bytes.size, sample.ptsUs, sample.flags) }
        muxer.writeSampleData(tracks[sample.track], ByteBuffer.wrap(sample.bytes), info)
    }
    private fun maybeNotify() {
        val summary = ledger.snapshot()
        if (!notified && started && summary.videoSamples > 0 && summary.audioSamples > 0) { notified = true; onMediaStarted() }
    }

    @Synchronized fun finish(): TrackSummary {
        check(!closed)
        closed = true
        val summary = ledger.snapshot()
        try {
            check(started && pending.isEmpty() && summary.videoSamples > 0 && summary.audioSamples > 0) { "Video- oder Tonspur fehlt" }
            muxer.stop()
            return summary
        } finally { muxer.release(); released = true }
    }

    @Synchronized fun abandon() {
        if (closed) return
        closed = true
        try { if (started) muxer.stop() } finally { muxer.release(); released = true }
    }
    @Synchronized fun isReleased(): Boolean = released
}
