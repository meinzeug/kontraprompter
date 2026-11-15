package de.kontrakurs.kontraprompter.speech

import de.kontrakurs.kontraprompter.video.PcmTee
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest
import java.util.UUID

/** Test-only messages: no real transport, microphone, recognizer or recorder. */
private data class Position(val recording: String, val script: String, val epoch: Long, val sequence: Long, val word: Int)
private class PositionReceiver(private val recording: String, private val script: String, private val epoch: Long) {
    private var sequence = 0L
    private var word = 0
    fun accept(message: Position): Boolean {
        if (message.recording != recording || message.script != script || message.epoch != epoch ||
            message.sequence <= sequence || message.word <= word) return false
        sequence = message.sequence; word = message.word
        return true
    }
}

/** Adapter to deliberately controlled fake recognition events, never wired to the app. */
private class SpeechFixture(scriptText: String) : AutoCloseable {
    private val tee = PcmTee()
    private val stream = tee.subscribe(capacity = 2)
    private val cursor = SpokenScriptCursor(spokenWords(scriptText))
    val recording: String = UUID.randomUUID().toString()
    val script = MessageDigest.getInstance("SHA-256").digest(scriptText.toByteArray())
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private var generation = tee.generation
    private var epoch = cursor.begin(0)
    private var expectedSample: Long? = null
    private var sequence = 0L
    private var active = true
    data class Ticket(val recording: String, val generation: Long, val epoch: Long, val segment: Long)

    fun publish(firstSample: Long, bytes: ByteArray) =
        tee.publish(recording, generation, firstSample, 1, 48_000, bytes, bytes.size)
    fun dropped() = stream.dropped
    fun next(segment: Long): Ticket? {
        val block = stream.poll() ?: return null
        if (!active || block.recordingId != recording || block.generation != generation ||
            (expectedSample != null && block.firstSample != expectedSample)) {
            pause(); return null
        }
        expectedSample = block.firstSample + block.samples.remaining() / 2
        return Ticket(block.recordingId, block.generation, epoch, segment)
    }
    fun result(ticket: Ticket, text: String, final: Boolean): Position? {
        if (!active || ticket.recording != recording || ticket.generation != generation || ticket.epoch != epoch) return null
        val word = cursor.accept(epoch, ticket.segment, text, final) ?: return null
        return Position(recording, script, epoch, ++sequence, word)
    }
    fun pause() { active = false; cursor.pause(); tee.invalidate(); generation = tee.generation }
    fun begin(anchor: Int) { tee.invalidate(); generation = tee.generation; epoch = cursor.begin(anchor); expectedSample = null; sequence = 0; active = true }
    override fun close() { pause(); stream.close(); tee.close() }
}

class SpeechPcmFixtureTest {
    private val script = "Hallo zusammen heute sprechen wir über Medien und Videos"
    @Test fun `synthetic audio advances only a matching final segment`() {
        SpeechFixture(script).use { f ->
            f.publish(0, byteArrayOf(1, 0, 2, 0))
            val ticket = f.next(0)!!
            assertNull(f.result(ticket, "Hallo zusammen", false))
            val position = f.result(ticket, "Hallo zusammen", true)!!
            assertEquals(2, position.word)
            assertNull(f.result(ticket, "Hallo zusammen heute", true))
            val receiver = PositionReceiver(f.recording, f.script, position.epoch)
            assertTrue(receiver.accept(position))
            assertFalse(receiver.accept(position))
            assertFalse(receiver.accept(position.copy(sequence = 2, word = 3, script = "0".repeat(64))))
            assertFalse(receiver.accept(position.copy(sequence = 2, word = 3, recording = UUID.randomUUID().toString())))
            assertFalse(receiver.accept(position.copy(sequence = 2, word = 3, epoch = position.epoch + 1)))
        }
    }

    @Test fun `silence pause and manual seek fence delayed recognition`() {
        SpeechFixture(script).use { f ->
            assertNull(f.next(0)) // no samples: no result or movement
            f.publish(0, byteArrayOf(1, 0)); val old = f.next(0)!!
            f.pause(); assertNull(f.result(old, "Hallo zusammen", true))
            f.begin(2); assertNull(f.result(old, "Hallo zusammen", true))
            f.publish(100, byteArrayOf(2, 0)); val fresh = f.next(0)!!
            assertEquals(4, f.result(fresh, "heute sprechen", true)!!.word)
        }
    }

    @Test fun `audio gap invalidates the segment instead of guessing through loss`() {
        SpeechFixture(script).use { f ->
            f.publish(0, byteArrayOf(1, 0)); val previous = f.next(0)!!
            f.publish(9, byteArrayOf(2, 0)); assertNull(f.next(1))
            assertNull(f.result(previous, "Hallo zusammen", true))
        }
    }

    @Test fun `slow analysis drops blocks without modifying recorder input`() {
        SpeechFixture(script).use { f ->
            val bytes = byteArrayOf(1, 0, 2, 0); val original = bytes.copyOf()
            repeat(10) { f.publish((it * 2).toLong(), bytes) }
            assertArrayEquals(original, bytes)
            assertEquals(8L, f.dropped())
            assertNotNull(f.next(0)); assertNotNull(f.next(1)); assertNull(f.next(2))
        }
    }

    @Test fun `reordered segments do not force cursor forward`() {
        SpeechFixture(script).use { f ->
            f.publish(0, byteArrayOf(1, 0)); val first = f.next(0)!!
            f.publish(1, byteArrayOf(2, 0)); val second = f.next(1)!!
            assertNull(f.result(first, "Hallo zusammen", false))
            assertNull(f.result(second, "heute sprechen", true))
            assertEquals(2, f.result(first, "Hallo zusammen", true)!!.word)
            assertEquals(4, f.result(second, "heute sprechen", true)!!.word)
        }
    }
}
