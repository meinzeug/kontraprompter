package de.kontrakurs.voskfixture

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.SystemClock
import de.kontrakurs.kontraprompter.speech.SpokenScriptCursor
import de.kontrakurs.kontraprompter.speech.spokenWords
import de.kontrakurs.kontraprompter.video.PcmTee
import de.kontrakurs.kontraprompter.video.SpeechLocalDeliveryFence
import java.io.DataInputStream
import java.security.MessageDigest
import java.util.Locale
import org.json.JSONObject
import org.junit.Assert.*
import org.vosk.Model
import org.vosk.Recognizer

/** Standalone instrumentation only: synthetic PCM, real AAC and offline Vosk, no AudioRecord or network. */
object SyntheticSpeechPipeline {
    private const val SIZE = 556_732
    private const val SHA = "cfcfe20034a34e79326187276cd8d5225284655239d82e24f65b6d0b5d9cc311"
    private const val SCRIPT = "Hallo zusammen heute sprechen wir über Medien und Videos."
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }

    @JvmStatic fun run(model: Model, test: Context) {
        val format = MediaFormat.createAudioFormat("audio/mp4a-latm", 48_000, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 96_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 8192)
        }
        val codec = MediaCodec.createEncoderByType("audio/mp4a-latm")
        val tee = PcmTee(); val channel = Any(); val generation = tee.generation
        val words = spokenWords(SCRIPT); val cursor = SpokenScriptCursor(words); val cursorEpoch = cursor.begin(0)
        val fence = SpeechLocalDeliveryFence(channel, "synthetic-take", "synthetic-v1", generation, words.size, 0)
        val subscription = tee.subscribe(8, fence)
        var started = false
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); codec.start(); started = true
            val recognizer = Recognizer(model, 48_000f)
            val engine = try { JnaVoskPcmEngine(recognizer) } catch (error: Throwable) { recognizer.close(); throw error }
            BoundedPcmDecoder(engine, 8).use { decoder ->
                val original = MessageDigest.getInstance("SHA-256"); val copied = MessageDigest.getInstance("SHA-256")
                val text = StringBuilder(); var sequence = 0L; var segment = 0L; var frames = 0L; var total = 0
                var encoded = 0L; var eos = false; val info = MediaCodec.BufferInfo()
                fun acceptJson(json: String) {
                    val phrase = JSONObject(json).optString("text", "").trim()
                    if (phrase.isEmpty()) return
                    text.append(' ').append(phrase)
                    val position = cursor.accept(cursorEpoch, segment++, phrase, true) ?: return
                    assertTrue(fence.apply(channel, "synthetic-take", "synthetic-v1", generation, ++sequence, position))
                }
                fun drain() {
                    while (true) {
                        val index = codec.dequeueOutputBuffer(info, 0)
                        if (index < 0) return
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) encoded += info.size
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) eos = true
                        codec.releaseOutputBuffer(index, false)
                    }
                }
                fun inputSlot(): Int {
                    val deadline = SystemClock.elapsedRealtime() + 5_000
                    while (SystemClock.elapsedRealtime() < deadline) {
                        val index = codec.dequeueInputBuffer(10_000)
                        if (index >= 0) return index
                        drain()
                    }
                    error("synthetic AAC input timed out")
                }
                DataInputStream(test.assets.open("synthetic-de-48khz-s16le.pcm")).use { input ->
                    val bytes = ByteArray(2048); var remaining = SIZE
                    while (remaining > 0) {
                        val n = minOf(bytes.size, remaining); input.readFully(bytes, 0, n); remaining -= n
                        original.update(bytes, 0, n)
                        val index = inputSlot(); val target = checkNotNull(codec.getInputBuffer(index))
                        target.clear(); check(target.remaining() >= n); target.put(bytes, 0, n)
                        codec.queueInputBuffer(index, 0, n, frames * 1_000_000 / 48_000, 0)
                        tee.publish("synthetic-take", generation, frames, 1, 48_000, bytes, n)
                        val block = checkNotNull(subscription.poll()); check(subscription.isCurrent(block))
                        val local = ByteArray(block.samples.remaining()); block.samples.get(local); copied.update(local)
                        assertTrue(decoder.offer(local, local.size, block.firstSample, block.sampleRate))
                        val result = decoder.pump(); assertNotEquals(BoundedPcmDecoder.Kind.FAILED, result.kind)
                        if (result.kind == BoundedPcmDecoder.Kind.FINAL) acceptJson(checkNotNull(result.json))
                        total += n; frames += n / 2; drain()
                    }
                    check(input.read() == -1)
                }
                assertEquals(SIZE, total); assertEquals(SHA, original.digest().hex()); assertEquals(SHA, copied.digest().hex())
                acceptJson(checkNotNull(decoder.finish()))
                codec.queueInputBuffer(inputSlot(), 0, 0, frames * 1_000_000 / 48_000, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                val deadline = SystemClock.elapsedRealtime() + 10_000
                while (!eos && SystemClock.elapsedRealtime() < deadline) { drain(); if (!eos) Thread.sleep(5) }
                assertTrue("AAC output missing", eos && encoded > 0)
                assertEquals("hallo zusammen heute sprechen wir über medien und videos", text.toString().lowercase(Locale.GERMANY).replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim())
                assertEquals(words.size, fence.positionOrNull() ?: -1)
                tee.invalidate(); assertNull(fence.positionOrNull())
            }
        } finally {
            subscription.close(); tee.close()
            if (started) runCatching { codec.stop() }; codec.release()
        }
    }
}
