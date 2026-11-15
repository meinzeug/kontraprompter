package de.kontrakurs.kontraprompter.camera

import java.io.IOException
import java.util.concurrent.CancellationException

internal class CameraProtocolException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Incompatible or disabled endpoints cannot provide commands; retain the recorder owner. */
internal fun throwIfCameraPollUnavailable(code: Int) {
    if (code in setOf(400, 404, 405, 410))
        throw CameraProtocolException("Die Kamerafernsteuerung ist nicht verfügbar oder inkompatibel (HTTP $code).")
}

/** Only wraps transport, never journal/recorder code; cancellation stays cancellation. */
internal inline fun <T> guardCameraTransport(block: () -> T): T = try { block() }
catch (error: CancellationException) { throw error }
catch (error: IOException) { throw error }
catch (error: Exception) { throw CameraUnexpectedTransportException(error) }

internal class CameraUnexpectedTransportException(cause: Throwable) : IOException("Kamerafernsteuerung unerwartet unterbrochen.", cause)
