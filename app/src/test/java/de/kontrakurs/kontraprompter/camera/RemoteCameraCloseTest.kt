package de.kontrakurs.kontraprompter.camera

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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

    @Test fun `identity owner is not released before an in-flight conflict reaches its old journal`() = runBlocking {
        val poller = SerialCameraPoll(); val entered = CompletableDeferred<Unit>(); val released = CompletableDeferred<Unit>()
        val transport = CountDownLatch(1); val events = Collections.synchronizedList(mutableListOf<String>())
        val oldDevice = java.util.UUID.randomUUID().toString(); val newDevice = java.util.UUID.randomUUID().toString()
        val poll = async {
            poller.poll(snapshot = { it }) {
                exchangeWithLateReportConflict(
                    exchange = { entered.complete(Unit); check(transport.await(3, TimeUnit.SECONDS)); throw CameraLateReportConflictException(oldDevice) },
                    persist = { conflict -> requireCameraConflictJournalIdentity(oldDevice, conflict.deviceId); events += "old-journal" },
                    onUnsafe = { events += "unsafe" },
                )
            }
        }
        try {
            withTimeout(2000) { entered.await() }
            val closing = async {
                finishCameraRemote(stop = { events += "stop" }, drain = { poller.closeAndDrain() }, awaitRecorder = {}, persist = {}, release = { events += "release"; released.complete(Unit) })
            }
            poll.cancel()
            assertNull(withTimeoutOrNull(100) { released.await() })
            assertThrows(IllegalStateException::class.java) { requireCameraConflictJournalIdentity(newDevice, oldDevice) }
            transport.countDown()
            withTimeout(3000) { closing.await() }
            assertEquals(listOf("stop", "old-journal", "release"), events.toList())
        } finally { transport.countDown() }
    }
}
