package de.kontrakurs.kontraprompter.speech

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class SpeechDeliveryFenceTest {
    private fun message(s: SpeechDeliveryFence.State, seq: Long = 1, pos: Int = 3): SpeechDeliveryFence.Message {
        val scope = requireNotNull(s.scope)
        return SpeechDeliveryFence.Message(scope.channel, scope.recording, scope.script, scope.audioGeneration, s.epoch, seq, pos)
    }
    private fun scope(channel: Any = Any(), recording: String = "take-a", script: String = "script-v1", audio: Long = 1) =
        SpeechDeliveryFence.Scope(channel, recording, script, audio, 20)

    @Test fun `seek and pause reject precomputed delayed positions`() {
        val fence = SpeechDeliveryFence(); val first = fence.start(scope(), 0); val pending = message(first)
        assertEquals(7, fence.seek(7).position); assertFalse(fence.apply(pending)); assertEquals(7, fence.snapshot().position)
        val second = fence.start(requireNotNull(first.scope), 7); val pending2 = message(second, pos = 9)
        fence.invalidate(); assertFalse(fence.apply(pending2)); assertEquals(7, fence.snapshot().position)
    }
    @Test fun `new recording script channel and audio generations reject old messages`() {
        val fence = SpeechDeliveryFence(); val initialScope = scope(); val first = fence.start(initialScope, 0); val old = message(first)
        val variants = listOf(scope(initialScope.channel, "take-b"), scope(initialScope.channel, script = "script-v2"),
            scope(), scope(initialScope.channel, audio = 2))
        for (next in variants) {
            val fresh = fence.start(next, 0); assertFalse(fence.apply(old)); assertEquals(0, fence.snapshot().position)
            assertTrue(fence.apply(message(fresh))); assertEquals(3, fence.snapshot().position)
        }
    }
    @Test fun `ui observes committed state and never a replay or out of range position`() {
        val fence = SpeechDeliveryFence(); val first = fence.start(scope(), 0); val result = message(first)
        assertFalse(fence.apply(result.copy(channel = Any())))
        assertFalse(fence.apply(result.copy(position = 21)))
        assertTrue(fence.apply(result)); assertFalse(fence.apply(result))
        assertEquals(3, fence.snapshot().position)
        val seek = fence.seek(1); assertFalse(seek.active); assertEquals(1, fence.snapshot().position)
        assertFalse(fence.apply(result.copy(sequence = 2, position = 4)))
    }
    @Test fun `invalidated loss before delivery stops even last outstanding result`() {
        val fence = SpeechDeliveryFence(); val first = fence.start(scope(), 0); val pending = message(first)
        fence.invalidate(); assertFalse(fence.apply(pending)); assertEquals(0, fence.snapshot().position)
    }
    @Test(timeout = 5000) fun `racing seek and result always leave the seek as last visible state`() {
        repeat(300) {
            val fence = SpeechDeliveryFence(); val first = fence.start(scope(), 0); val pending = message(first)
            val go = CountDownLatch(1); val done = CountDownLatch(2)
            val a = Thread { try { go.await(); fence.apply(pending) } finally { done.countDown() } }
            val b = Thread { try { go.await(); fence.seek(7) } finally { done.countDown() } }
            a.isDaemon = true; b.isDaemon = true; a.start(); b.start(); go.countDown()
            assertTrue(done.await(2, TimeUnit.SECONDS)); assertEquals(7, fence.snapshot().position); assertFalse(fence.snapshot().active)
        }
    }
}
