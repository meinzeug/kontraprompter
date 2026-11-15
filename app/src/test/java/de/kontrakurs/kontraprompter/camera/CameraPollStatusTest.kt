package de.kontrakurs.kontraprompter.camera

import org.junit.Assert.*
import java.io.IOException
import java.util.concurrent.CancellationException
import org.junit.Test

class CameraPollStatusTest {
    @Test fun disabledAndOlderEndpointResponsesUseTheNonDestructiveProtocolPath() {
        for (code in listOf(400, 404, 405, 410)) {
            val error = runCatching { throwIfCameraPollUnavailable(code) }.exceptionOrNull()
            assertTrue("HTTP $code must use the runtime's protocol retry branch", error is CameraProtocolException)
        }
    }
    @Test fun unrelatedStatusesRemainForTheirExistingSpecificHandling() {
        for (code in listOf(200, 401, 403, 409, 429, 500, 503)) {
            assertTrue("HTTP $code must not be mistaken for endpoint absence", runCatching { throwIfCameraPollUnavailable(code) }.isSuccess)
        }
    }
    @Test fun unexpectedTransportErrorsAreWrappedButCancellationAndIoKeepTheirTypes() {
        val authFailure = IllegalStateException("expired")
        assertSame(authFailure, (runCatching { guardCameraTransport { throw authFailure } }.exceptionOrNull() as CameraUnexpectedTransportException).cause)
        val network = IOException("timeout")
        assertSame(network, runCatching { guardCameraTransport { throw network } }.exceptionOrNull())
        val cancelled = CancellationException("cancel")
        assertSame(cancelled, runCatching { guardCameraTransport { throw cancelled } }.exceptionOrNull())
        val protocol = CameraProtocolException("404")
        assertSame(protocol, runCatching { guardCameraTransport { throw protocol } }.exceptionOrNull())
    }
}
