package de.kontrakurs.kontraprompter

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.*
import org.junit.Test

class PairingQrTest {
    private val secret = "0123456789abcdef0123456789abcdef" // synthetic test value only
    private val now = 1_700_000_000_000L

    @Test fun imageCanBeDecodedAndValidated() {
        val value = PairingQr.encode("192.168.1.23", 12345, secret, now)!!
        val matrix = QRCodeWriter().encode(value, BarcodeFormat.QR_CODE, 512, 512, mapOf(EncodeHintType.MARGIN to 4))
        val pixels = IntArray(512 * 512) { if (matrix[it % 512, it / 512]) 0xff000000.toInt() else 0xffffffff.toInt() }
        val result = MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(512, 512, pixels))))
        val decoded = PairingQr.decode(result.text, now)!!
        assertEquals("192.168.1.23", decoded.address)
        assertEquals(12345, decoded.port)
        assertEquals(secret, decoded.token)
        assertEquals(now + 60_000L, decoded.expiresAt)
    }

    @Test fun expiresAndRejectsUnexpectedValues() {
        val value = PairingQr.encode("10.1.2.3", 456, secret, now)!!
        assertNotNull(PairingQr.decode(value, now + 59_999))
        assertNull(PairingQr.decode(value, now + 60_000))
        assertNull(PairingQr.decode(value, now - 30_001))
        assertNull(PairingQr.decode(value.replace("kp1", "kp2"), now))
        assertNull(PairingQr.decode(value + "|extra", now))
        assertNull(PairingQr.decode(value.replace("10.1.2.3", "127.0.0.1"), now))
        assertNull(PairingQr.decode(value.replace("10.1.2.3", "example.org"), now))
        assertNull(PairingQr.decode(value.replace("10.1.2.3", "10.999.2.3"), now))
        assertNull(PairingQr.decode(value.replace("|456|", "|65536|"), now))
        assertNull(PairingQr.decode(value.replace(secret, "bad-token"), now))
        assertNull(PairingQr.decode("x".repeat(161), now))
    }

    @Test fun scannerRetriesInvalidCodesButDeliversOneValidCode() {
        val gate = QrScanGate()
        var calls = 0
        gate.submit("invalid") { calls++; false }
        gate.submit("valid") { calls++; true }
        gate.submit("valid") { calls++; true }
        assertEquals(2, calls)
    }
}
