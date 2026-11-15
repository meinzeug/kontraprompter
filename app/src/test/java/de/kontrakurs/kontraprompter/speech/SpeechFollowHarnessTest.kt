package de.kontrakurs.kontraprompter.speech

import de.kontrakurs.kontraprompter.video.PcmTee
import org.junit.Assert.*
import org.junit.Test

class SpeechFollowHarnessTest {
    private val text = "Hallo zusammen heute sprechen wir über Medien und Videos"

    @Test fun `bounded real tee reaches final position only for same ownership`() {
        val tee = PcmTee(); val stream = tee.subscribe(2); val channel = Any()
        val adapter = SpeechFollowHarness("take", "script-v1", channel, text, { tee.generation }, { stream.dropped })
        adapter.begin(0)
        try {
            tee.publish("take", tee.generation, 0, 1, 48_000, byteArrayOf(1, 0, 2, 0), 4)
            val ticket = adapter.input(stream.poll()!!, 0)!!
            assertNull(adapter.result(ticket, "Hallo zusammen", false))
            val position = adapter.result(ticket, "Hallo zusammen", true)!!
            val receiver = SpeechPositionHarness(channel, "take", "script-v1", { tee.generation }, { stream.dropped }, { channel })
            receiver.bind(position.revision, 0)
            assertFalse(receiver.apply(position.copy(channel = Any())))
            assertFalse(receiver.apply(position.copy(recording = "other")))
            assertFalse(receiver.apply(position.copy(scriptRevision = "other")))
            assertTrue(receiver.apply(position)); assertFalse(receiver.apply(position))
        } finally { stream.close(); tee.close() }
    }

    @Test fun `late result after recorder epoch change or manual seek is rejected`() {
        val tee = PcmTee(); val stream = tee.subscribe(); val channel = Any()
        val adapter = SpeechFollowHarness("take", "v1", channel, text, { tee.generation }, { stream.dropped })
        adapter.begin(0)
        try {
            tee.publish("take", tee.generation, 0, 1, 48_000, byteArrayOf(1, 0), 2)
            val old = adapter.input(stream.poll()!!, 0)!!
            tee.invalidate(); assertNull(adapter.result(old, "Hallo zusammen", true))
            adapter.begin(0)
            tee.publish("take", tee.generation, 10, 1, 48_000, byteArrayOf(1, 0), 2)
            val fresh = adapter.input(stream.poll()!!, 0)!!
            val msg = adapter.result(fresh, "Hallo zusammen", true)!!
            val receiver = SpeechPositionHarness(channel, "take", "v1", { tee.generation }, { stream.dropped }, { channel })
            receiver.bind(msg.revision + 1, 0) // seek happened while delivery was in flight
            assertFalse(receiver.apply(msg))
            adapter.pause(); assertNull(adapter.result(fresh, "Hallo zusammen", true))
        } finally { stream.close(); tee.close() }
    }

    @Test fun `dropped samples stop analysis instead of advancing through gap`() {
        val tee = PcmTee(); val stream = tee.subscribe(1); val channel = Any()
        val adapter = SpeechFollowHarness("take", "v1", channel, text, { tee.generation }, { stream.dropped })
        adapter.begin(0)
        try {
            tee.publish("take", tee.generation, 0, 1, 48_000, byteArrayOf(1, 0), 2)
            val prior = adapter.input(stream.poll()!!, 0)!!
            tee.publish("take", tee.generation, 1, 1, 48_000, byteArrayOf(1, 0), 2)
            tee.publish("take", tee.generation, 2, 1, 48_000, byteArrayOf(1, 0), 2)
            assertEquals(1L, stream.dropped)
            assertNull(adapter.result(prior, "Hallo zusammen", true)) // no successor block needed
            assertNull(adapter.input(stream.poll()!!, 1))
            assertNull(adapter.result(prior, "Hallo zusammen", true))
        } finally { stream.close(); tee.close() }
    }

    @Test fun `already created position is rejected after audio invalidation or channel change`() {
        val tee = PcmTee(); val stream = tee.subscribe(1); val channel = Any(); var active: Any? = channel
        val adapter = SpeechFollowHarness("take", "v1", channel, text, { tee.generation }, { stream.dropped })
        adapter.begin(0)
        try {
            tee.publish("take", tee.generation, 0, 1, 48_000, byteArrayOf(1, 0), 2)
            val ticket = adapter.input(stream.poll()!!, 0)!!
            val result = adapter.result(ticket, "Hallo zusammen", true)!!
            val receiver = SpeechPositionHarness(channel, "take", "v1", { tee.generation }, { stream.dropped }, { active })
            receiver.bind(result.revision, 0)
            active = Any(); assertFalse(receiver.apply(result)); active = channel
            tee.invalidate(); assertFalse(receiver.apply(result))
        } finally { stream.close(); tee.close() }
    }

    @Test fun `atomic position fixture rejects precomputed position after loss invalidation`() {
        val channel = Any()
        val fence = SpeechAtomicPositionFixture(channel, "take", "v1", 1, 0, 0)
        val pending = SpeechFollowHarness.Position(channel, "take", "v1", 1, 1, 2, 1, 0)
        fence.invalidate() // simulates loss or channel/seek change BEFORE delivery
        assertFalse(fence.apply(pending)); assertEquals(0, fence.snapshot().position)
    }
}
