package de.kontrakurs.kontraprompter.network

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraStatusTest {
    @Test fun firstFrameUpdatesOnlyThePreparingState() {
        val preparing = LanState(ready = true, status = "Gekoppelt; Video wird vorbereitet")
        assertEquals("Kamerabild verfügbar; Verbindung wird aufgebaut", firstCameraFrame(preparing).status)
    }
    @Test fun lateFrameCannotOverwriteConnectionOrFailure() {
        val connected = LanState(ready = true, status = "Videoverbindung hergestellt")
        val error = LanState(ready = true, status = "Videofehler", error = "Kamera nicht verfügbar")
        val disconnected = LanState(ready = false, status = "Nicht verbunden", error = "Verbindung verloren")
        listOf(connected, error, disconnected).forEach { assertEquals(it, firstCameraFrame(it)) }
    }
    @Test fun frameCannotClearExistingPreparationError() {
        val errored = LanState(ready = true, status = "Gekoppelt; Video wird vorbereitet", error = "Kamerafehler")
        assertEquals(errored, firstCameraFrame(errored))
    }
}
