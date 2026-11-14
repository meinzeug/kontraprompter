package de.kontrakurs.kontraprompter.network

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.SocketTimeoutException

class SignalFrameTest {
    @Test fun roundTripPreservesOrderAndUtf8() {
        val bytes = ByteArrayOutputStream()
        val writer = SignalFrameWriter(DataOutputStream(bytes))
        writer.write("sdp", "ä Kamera")
        writer.write("capture_command", "{}")
        val reader = SignalFrameReader(DataInputStream(ByteArrayInputStream(bytes.toByteArray())))
        assertEquals("sdp" to "ä Kamera", reader.read())
        assertEquals("capture_command" to "{}", reader.read())
    }
    @Test fun rejectsReplayOversizeAndInvalidUtf8() {
        fun reader(bytes: ByteArray) = SignalFrameReader(DataInputStream(ByteArrayInputStream(bytes)))
        fun frame(sequence: Long, name: ByteArray, body: ByteArray): ByteArray = ByteArrayOutputStream().also {
            DataOutputStream(it).apply { writeLong(sequence); writeInt(name.size); write(name); writeInt(body.size); write(body) }
        }.toByteArray()
        val repeated = frame(1, "sdp".toByteArray(), byteArrayOf())
        val replay = reader(repeated + repeated); replay.read()
        assertThrows(IOException::class.java) { replay.read() }
        assertThrows(IOException::class.java) { reader(frame(1, "sdp".toByteArray(), byteArrayOf(0xc3.toByte(), 0x28))).read() }
        val oversized = ByteArrayOutputStream().also { DataOutputStream(it).apply { writeLong(1); writeInt(33) } }.toByteArray()
        assertThrows(IOException::class.java) { reader(oversized).read() }
    }
    @Test fun writerRejectsInvalidMessageBeforeWriting() {
        val bytes = ByteArrayOutputStream(); val writer = SignalFrameWriter(DataOutputStream(bytes))
        assertThrows(IllegalArgumentException::class.java) { writer.write("bad type", "") }
        assertThrows(IllegalArgumentException::class.java) { writer.write("sdp", "x".repeat(1_048_577)) }
        assertThrows(IllegalArgumentException::class.java) { writer.write("sdp", "\uD800") }
        assertEquals(0, bytes.size())
    }
    @Test fun slowFrameHasTotalDeadlineAndIdleUsesSeparateTimeout() {
        val bytes = ByteArrayOutputStream()
        SignalFrameWriter(DataOutputStream(bytes)).write("sdp", "ok")
        var nanos = 0L
        val source = object : InputStream() {
            private val delegate = ByteArrayInputStream(bytes.toByteArray())
            override fun read(): Int { nanos += 2_000_000_000L; return delegate.read() }
        }
        val timeouts = mutableListOf<Int>()
        val timed = DeadlineInput(source, { timeouts.add(it) }, { nanos })
        val reader = SignalFrameReader(DataInputStream(timed), timed::beginFrame, timed::endFrame)
        assertThrows(SocketTimeoutException::class.java) { reader.read() }
        assertEquals(30_000, timeouts.first())
        assertEquals(30_000, timeouts.last())
        assertTrue(timeouts.any { it in 1 until 15_000 })
    }

}
