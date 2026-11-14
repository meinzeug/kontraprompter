package de.kontrakurs.kontraprompter.camera

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

class SerialCameraPollTest {
    @Test fun `delayed poll cannot send an older snapshot with a later sequence`() = runBlocking {
        val poll = SerialCameraPoll()
        val phase = AtomicReference("preparing")
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        val first = async {
            poll.poll(snapshot = { it to phase.get() }) { report -> firstStarted.complete(Unit); releaseFirst.await(); report }
        }
        withTimeout(2000) { firstStarted.await() }
        phase.set("recording")
        val second = async {
            poll.poll(snapshot = { it to phase.get() }) { report -> secondStarted.complete(Unit); report }
        }
        assertNull(withTimeoutOrNull(100) { secondStarted.await() })
        releaseFirst.complete(Unit)
        assertEquals(1L to "preparing", first.await())
        assertEquals(2L to "recording", withTimeout(2000) { second.await() })
    }

    @Test fun `failed exchange consumes its sequence`() = runBlocking {
        val poll = SerialCameraPoll()
        runCatching { poll.poll(snapshot = { it }) { throw IllegalStateException("lost response") } }
        assertEquals(2L, poll.poll(snapshot = { it }) { it })
    }
}
