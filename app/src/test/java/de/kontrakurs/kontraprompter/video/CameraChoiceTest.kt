package de.kontrakurs.kontraprompter.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CameraChoiceTest {
    @Test fun preferenceAndFallbackAreDeterministic() {
        val cameras = listOf("back", "front")
        val front: (String) -> Boolean = { it == "front" }
        val back: (String) -> Boolean = { it == "back" }
        assertEquals("front", chooseCamera(cameras, true, front, back))
        assertEquals("back", chooseCamera(cameras, false, front, back))
        assertEquals("back", chooseCamera(listOf("back"), true, front, back))
        assertNull(chooseCamera(emptyList(), true, front, back))
    }
}
