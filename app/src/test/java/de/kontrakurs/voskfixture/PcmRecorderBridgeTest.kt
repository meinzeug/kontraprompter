package de.kontrakurs.voskfixture

import de.kontrakurs.kontraprompter.video.PcmTee
import org.junit.Assert.*
import org.junit.Test

class PcmRecorderBridgeTest {
    private class Fake(private val onAccept: () -> Unit = {}) : BoundedPcmDecoder.Engine {
        var seen: ByteArray? = null; var closes = 0; var calls = 0
        override fun accept(pcm: ByteArray): Int { seen = pcm; calls++; onAccept(); return 1 }
        override fun result(): String = "{\"text\":\"synthetic\"}"
        override fun finalResult(): String = "{}"
        override fun close() { closes++ }
    }

    @Test fun `shared tee PCM reaches decoder without changing recorder bytes`() {
        val tee = PcmTee(); val sub = tee.subscribe(2); val fake = Fake(); val decoder = BoundedPcmDecoder(fake, 2)
        val original = byteArrayOf(1, 0, 2, 0); val copy = original.copyOf()
        PcmRecorderBridge(tee, sub, decoder, "take").use { bridge ->
            tee.publish("take", tee.generation, 5, 1, 48_000, original, original.size)
            original[0] = 7 // producer buffer may be reused immediately
            assertEquals(BoundedPcmDecoder.Kind.FINAL, bridge.pump().kind)
            assertArrayEquals(copy, fake.seen)
            assertEquals(BoundedPcmDecoder.Kind.EMPTY, bridge.pump().kind)
        }
        assertEquals(1, fake.closes); tee.close()
    }

    @Test fun `recorder generation changes during native call discard result`() {
        val tee = PcmTee(); val sub = tee.subscribe(2); val fake = Fake { tee.invalidate() }
        PcmRecorderBridge(tee, sub, BoundedPcmDecoder(fake, 2), "take").use { bridge ->
            tee.publish("take", tee.generation, 0, 1, 48_000, byteArrayOf(1, 0), 2)
            val result = bridge.pump(); assertEquals(BoundedPcmDecoder.Kind.FAILED, result.kind); assertNull(result.json)
        }
        tee.close()
    }

    @Test fun `queue overflow during native call discards even final result`() {
        val tee = PcmTee(); val sub = tee.subscribe(1)
        val fake = Fake {
            tee.publish("take", tee.generation, 1, 1, 48_000, byteArrayOf(2, 0), 2)
            tee.publish("take", tee.generation, 2, 1, 48_000, byteArrayOf(3, 0), 2)
        }
        PcmRecorderBridge(tee, sub, BoundedPcmDecoder(fake, 1), "take").use { bridge ->
            tee.publish("take", tee.generation, 0, 1, 48_000, byteArrayOf(1, 0), 2)
            val result = bridge.pump(); assertEquals(1L, sub.dropped); assertEquals(BoundedPcmDecoder.Kind.FAILED, result.kind); assertNull(result.json)
        }
        tee.close()
    }

    @Test fun `wrong recording and sample gap never reach native decoder`() {
        val tee = PcmTee(); val sub = tee.subscribe(); val fake = Fake()
        PcmRecorderBridge(tee, sub, BoundedPcmDecoder(fake, 2), "take").use { bridge ->
            tee.publish("wrong", tee.generation, 0, 1, 48_000, byteArrayOf(1, 0), 2)
            assertEquals(BoundedPcmDecoder.Kind.FAILED, bridge.pump().kind); assertEquals(0, fake.calls)
        }
        tee.close()
        val second = PcmTee(); val next = second.subscribe(); val fake2 = Fake()
        PcmRecorderBridge(second, next, BoundedPcmDecoder(fake2, 2), "take").use { bridge ->
            second.publish("take", second.generation, 0, 1, 48_000, byteArrayOf(1, 0), 2)
            assertEquals(BoundedPcmDecoder.Kind.FINAL, bridge.pump().kind)
            second.publish("take", second.generation, 5, 1, 48_000, byteArrayOf(1, 0), 2)
            assertEquals(BoundedPcmDecoder.Kind.FAILED, bridge.pump().kind); assertEquals(1, fake2.calls)
        }
        second.close()
    }
}
