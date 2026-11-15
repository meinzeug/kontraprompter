package de.kontrakurs.voskfixture

import de.kontrakurs.kontraprompter.video.PcmTee
import org.junit.Assert.*
import org.junit.Test

class LocalSpeechSessionTest {
    private class Engine(private val action: () -> Unit = {}, private val status: Int = 1) : BoundedPcmDecoder.Engine {
        var calls = 0; var closes = 0
        override fun accept(pcm: ByteArray): Int { calls++; action(); return status }
        override fun result() = "hallo zusammen"
        override fun finalResult() = ""
        override fun close() { closes++ }
    }
    private fun session(tee: PcmTee, engine: Engine, anchor: Int = 0, capacity: Int = 2) =
        LocalSpeechSession(tee, BoundedPcmDecoder(engine, 2), Any(), "take", "script", "hallo zusammen heute", anchor, { it }, capacity)
    private fun publish(tee: PcmTee, sample: Long, recording: String = "take") =
        tee.publish(recording, tee.generation, sample, 1, 48_000, byteArrayOf(1, 0), 2)

    @Test fun `final decoder result advances only its local scope`() {
        val tee = PcmTee(); val engine = Engine()
        session(tee, engine).use { s -> publish(tee, 0); assertTrue(s.pump()); assertEquals(2, s.positionOrNull()) }
        assertEquals(1, engine.closes); tee.close()
    }
    @Test fun `loss during native result permanently invalidates even with no further audio`() {
        val tee = PcmTee()
        val engine = Engine(action = { publish(tee, 1); publish(tee, 2) })
        session(tee, engine, capacity = 1).use { s ->
            publish(tee, 0); assertFalse(s.pump()); assertNull(s.positionOrNull()); assertFalse(s.pump())
        }
        tee.close()
    }
    @Test fun `pause during native call invalidates pending position`() {
        val tee = PcmTee(); lateinit var s: LocalSpeechSession
        val engine = Engine(action = { s.invalidate() })
        s = session(tee, engine)
        s.use { publish(tee, 0); assertFalse(s.pump()); assertNull(s.positionOrNull()) }; tee.close()
    }
    @Test fun `seek and restart use new queue and reject old buffered audio`() {
        val tee = PcmTee(); val oldEngine = Engine(); val old = session(tee, oldEngine)
        publish(tee, 0); old.invalidate()
        val newEngine = Engine(); val fresh = session(tee, newEngine, anchor = 1)
        assertFalse(old.pump()); assertTrue(fresh.pump()); assertEquals(0, newEngine.calls)
        assertEquals(1, fresh.positionOrNull()); assertNull(old.positionOrNull())
        old.close(); fresh.close(); tee.close()
    }
    @Test fun `audio invalidation and wrong take fail closed`() {
        val tee = PcmTee(); val engine = Engine()
        session(tee, engine).use { s -> publish(tee, 0); tee.invalidate(); assertFalse(s.pump()); assertNull(s.positionOrNull()) }
        val next = Engine()
        session(tee, next).use { s -> publish(tee, 0, "different"); assertFalse(s.pump()); assertEquals(0, next.calls) }
        tee.close()
    }
    @Test fun `decoder error and sample gap cannot advance position`() {
        val tee = PcmTee(); val bad = Engine(status = -1)
        session(tee, bad).use { s -> publish(tee, 0); assertFalse(s.pump()); assertNull(s.positionOrNull()) }
        val normal = Engine()
        session(tee, normal).use { s ->
            publish(tee, 0); assertTrue(s.pump()); publish(tee, 4)
            assertFalse(s.pump()); assertNull(s.positionOrNull()); assertEquals(1, normal.calls)
        }
        tee.close()
    }
    @Test fun `loss after decoding but before position commit invalidates the same fence`() {
        val tee = PcmTee(); val engine = Engine()
        val s = LocalSpeechSession(tee, BoundedPcmDecoder(engine, 2), Any(), "take", "script",
            "hallo zusammen heute", 0, { text -> publish(tee, 1); publish(tee, 2); text }, 1)
        s.use { publish(tee, 0); assertFalse(s.pump()); assertNull(s.positionOrNull()) }
        tee.close()
    }
    @Test fun `nonfinal and malformed final results do not advance`() {
        val tee = PcmTee(); val partial = Engine(status = 0)
        session(tee, partial).use { s -> publish(tee, 0); assertTrue(s.pump()); assertEquals(0, s.positionOrNull()) }
        val badText = Engine()
        val s = LocalSpeechSession(tee, BoundedPcmDecoder(badText, 2), Any(), "take", "script",
            "hallo zusammen heute", 0, { null })
        s.use { publish(tee, 1); assertFalse(s.pump()); assertNull(s.positionOrNull()) }
        tee.close()
    }
}
