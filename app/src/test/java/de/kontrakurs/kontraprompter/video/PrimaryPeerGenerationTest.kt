package de.kontrakurs.kontraprompter.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrimaryPeerGenerationTest {
    @Test fun `delayed initial start retains the token from pipeline creation`() {
        val generation = PrimaryPeerGeneration()
        val initialToken = generation.current()
        generation.invalidate() // disconnect after pipeline creation, before start is invoked
        var captureStarted = false
        if (generation.isCurrent(initialToken)) captureStarted = true
        assertFalse(captureStarted)
    }

    @Test fun `queued callbacks from an old peer cannot update a replacement`() {
        val generation = PrimaryPeerGeneration()
        val oldPeer = generation.current()
        val applied = mutableListOf<String>()
        val pending = mutableListOf<() -> Unit>()
        fun enqueue(token: Long, label: String) { pending += { if (generation.isCurrent(token)) applied += label } }
        enqueue(oldPeer, "old SDP")
        enqueue(oldPeer, "old ICE")
        enqueue(oldPeer, "old track")
        generation.invalidate()
        val newPeer = generation.current()
        enqueue(newPeer, "new SDP")
        enqueue(newPeer, "new ICE")
        pending.forEach { it() }
        assertEquals(listOf("new SDP", "new ICE"), applied)
    }

    @Test fun `repeated disconnects do not revive earlier tokens`() {
        val generation = PrimaryPeerGeneration()
        val first = generation.current()
        generation.invalidate()
        val second = generation.current()
        generation.invalidate()
        assertFalse(generation.isCurrent(first))
        assertFalse(generation.isCurrent(second))
        assertTrue(generation.isCurrent(generation.current()))
    }
}
