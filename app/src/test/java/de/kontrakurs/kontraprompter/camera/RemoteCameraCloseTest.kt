package de.kontrakurs.kontraprompter.camera

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RemoteCameraCloseTest {
    @Test fun `journal failure releases owner only after stop drain and recorder termination`() = runBlocking {
        val events = mutableListOf<String>()
        val failure = IllegalStateException("journal failure")
        val actual = runCatching {
            finishCameraRemote(
                stop = { events += "stop" }, drain = { events += "drain" },
                awaitRecorder = { events += "stopped" }, persist = { events += "persist"; throw failure },
                release = { events += "release" },
            )
        }.exceptionOrNull()
        assertSame(failure, actual)
        assertEquals(listOf("stop", "drain", "stopped", "persist", "release"), events)
    }
    @Test fun `failure before transport drain retains ownership`() = runBlocking {
        val events = mutableListOf<String>()
        runCatching {
            finishCameraRemote(
                stop = { events += "stop" }, drain = { throw IllegalStateException("socket") },
                awaitRecorder = { events += "stopped" }, persist = { events += "persist" }, release = { events += "release" },
            )
        }.onSuccess { fail("drain failure must propagate") }
        assertEquals(listOf("stop"), events)
    }
    @Test fun `recorder failure retains ownership even after drain`() = runBlocking {
        val events = mutableListOf<String>()
        runCatching {
            finishCameraRemote(
                stop = { events += "stop" }, drain = { events += "drain" }, awaitRecorder = { throw IllegalStateException("recorder") },
                persist = { events += "persist" }, release = { events += "release" },
            )
        }.onSuccess { fail("recorder failure must propagate") }
        assertEquals(listOf("stop", "drain"), events)
    }
}
