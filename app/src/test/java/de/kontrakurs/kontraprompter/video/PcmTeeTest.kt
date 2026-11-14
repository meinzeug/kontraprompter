package de.kontrakurs.kontraprompter.video

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteOrder

class PcmTeeTest {
    @Test fun subscribersReceiveImmutableCopiesAndIndependentPositions() {
        val tee = PcmTee(); val first = tee.subscribe(); val second = tee.subscribe()
        val audio = byteArrayOf(1, 2, 3, 4)
        tee.publish("take", tee.generation, 32, 1000, 48_000, audio, audio.size)
        audio[0] = 99
        val a = first.poll()!!; val b = second.poll()!!
        assertTrue(a.samples.isReadOnly); assertEquals(1, a.samples.get().toInt())
        assertEquals(1, b.samples.get().toInt()); assertEquals(32L, a.firstSample)
        b.samples.position(0)
        val expected = if (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN) 0x0201 else 0x0102
        assertEquals(expected, b.samples.short.toInt())
        assertEquals(48_000, a.sampleRate)
        tee.close(); assertNull(first.poll()); assertNull(second.poll())
    }
    @Test fun slowConsumerHasBoundedQueueAndVisibleGaps() {
        val tee = PcmTee(); val sub = tee.subscribe(capacity = 1)
        val audio = byteArrayOf(1, 2)
        tee.publish("take", tee.generation, 0, 100, 48_000, audio, 2)
        tee.publish("take", tee.generation, 1, 200, 48_000, audio, 2)
        assertEquals(1L, sub.dropped); assertEquals(0L, sub.poll()!!.firstSample)
        tee.publish("take", tee.generation, 2, 300, 48_000, audio, 2)
        assertEquals(2L, sub.poll()!!.firstSample)
        sub.close(); tee.close()
    }
    @Test fun closeAndInvalidFramesNeverReachConsumers() {
        val tee = PcmTee(); val sub = tee.subscribe()
        tee.publish("take", tee.generation, 0, 100, 48_000, byteArrayOf(1), 1)
        assertNull(sub.poll())
        sub.close(); tee.publish("take", tee.generation, 0, 100, 48_000, byteArrayOf(1, 2), 2)
        assertNull(sub.poll()); tee.close()
        assertThrows(IllegalStateException::class.java) { tee.subscribe() }
    }
    @Test fun pauseOrRestartFencesBufferedAndInFlightAudio() {
        val tee = PcmTee(); val sub = tee.subscribe(); val old = tee.generation
        val data = byteArrayOf(1, 2)
        tee.publish("old", old, 0, 100, 48_000, data, 2)
        tee.invalidate()
        tee.publish("old", old, 1, 200, 48_000, data, 2)
        assertNull(sub.poll())
        tee.publish("new", tee.generation, 0, 300, 48_000, data, 2)
        val fresh = sub.poll()!!
        assertEquals("new", fresh.recordingId)
        assertEquals(tee.generation, fresh.generation)
        tee.close()
    }

}
