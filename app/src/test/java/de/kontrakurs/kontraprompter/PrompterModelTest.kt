package de.kontrakurs.kontraprompter

import org.junit.Assert.*
import org.junit.Test

class PrompterModelTest {
    @Test fun scrollUsesTimeAndDensity() { assertEquals(150f, ScrollMath.distance(30f, 2.5f, 2f), .001f) }
    @Test fun negativeElapsedTimeDoesNotMove() { assertEquals(0f, ScrollMath.distance(30f, -1f, 2f), .001f) }
    @Test fun transformsAreIndependent() {
        val text = LayerTransform(mirrorHorizontal = true).rotate()
        val video = LayerTransform(mirrorVertical = true)
        assertEquals(-1f, text.scaleX, 0f); assertEquals(1f, text.scaleY, 0f); assertEquals(90, text.rotation)
        assertEquals(1f, video.scaleX, 0f); assertEquals(-1f, video.scaleY, 0f); assertEquals(0, video.rotation)
        assertEquals(0, text.rotate().rotate().rotate().rotation)
    }
}
