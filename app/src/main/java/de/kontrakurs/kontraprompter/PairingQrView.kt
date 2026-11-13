package de.kontrakurs.kontraprompter

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.ConnectivityManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.BarcodeView
import com.journeyapps.barcodescanner.DefaultDecoderFactory
import kotlinx.coroutines.delay

@Composable
internal fun PairingQrDisplay(addresses: List<String>, port: Int, token: String) {
    val context = LocalContext.current
    var now by remember(token) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(token) { while (true) { now = System.currentTimeMillis(); delay(40_000) } }
    val payload = remember(addresses, port, token, now) {
        // Prefer the active interface when a VPN or cellular interface is also present.
        val preferred = runCatching {
            val connectivity = context.getSystemService(ConnectivityManager::class.java)
            connectivity.getLinkProperties(connectivity.activeNetwork)?.linkAddresses.orEmpty().mapNotNull { it.address.hostAddress }
        }.getOrDefault(emptyList())
        (preferred.filter { it in addresses } + addresses).distinct().firstNotNullOfOrNull { PairingQr.encode(it, port, token, now) }
    }
    val bitmap = remember(payload) { payload?.let { content -> runCatching {
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 512, 512, mapOf(EncodeHintType.MARGIN to 4))
        val pixels = IntArray(512 * 512) { index -> if (matrix[index % 512, index / 512]) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
        Bitmap.createBitmap(pixels, 512, 512, Bitmap.Config.ARGB_8888).asImageBitmap()
    }.getOrNull() } }
    if (bitmap != null) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Image(bitmap, contentDescription = "QR-Code zur Kopplung", modifier = Modifier.sizeIn(maxWidth = 240.dp, maxHeight = 240.dp).fillMaxWidth().aspectRatio(1f).background(Color.White))
        }
        Text("Auf dem Teleprompter ‚QR-Code scannen‘ wählen. Der Code erneuert sich regelmäßig.", color = SoftText, style = MaterialTheme.typography.bodySmall)
    } else Text("QR-Code noch nicht verfügbar. Netzwerk prüfen oder manuelle Kopplung verwenden.", color = SoftText, style = MaterialTheme.typography.bodySmall)
}

@Composable
internal fun PairingQrScanner(onPair: (QrPairing) -> Unit) {
    val context = LocalContext.current
    var scanning by remember { mutableStateOf(false) }
    var permitted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var error by remember { mutableStateOf<String?>(null) }
    val requestCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permitted = granted
        scanning = granted
        if (!granted) error = "Kamerazugriff nicht erlaubt. Du kannst die Daten auch manuell eingeben."
    }
    if (!scanning) {
        MainAction("QR-Code scannen") {
            error = null
            permitted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            if (permitted) scanning = true else requestCamera.launch(Manifest.permission.CAMERA)
        }
    } else {
        Text("Richte die Kamera auf den QR-Code des Kamerahandys.", color = SoftText, style = MaterialTheme.typography.bodyMedium)
        QrCameraPreview(onValue = { value ->
            val decoded = PairingQr.decode(value, System.currentTimeMillis())
            if (decoded == null) { error = "Ungültiger oder abgelaufener QR-Code. Bitte den aktuellen Code scannen."; false }
            else { scanning = false; error = null; onPair(decoded); true }
        })
        TextButton(onClick = { scanning = false }) { Text("Scan beenden") }
    }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
}

@Composable
private fun QrCameraPreview(onValue: (String) -> Boolean) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val latestValue by rememberUpdatedState(onValue)
    val delivered = remember { QrScanGate() }
    val view = remember {
        BarcodeView(context).apply {
            decoderFactory = DefaultDecoderFactory(listOf(BarcodeFormat.QR_CODE))
            decodeContinuous(object : BarcodeCallback {
                override fun barcodeResult(result: BarcodeResult?) {
                    val text = result?.text ?: return
                    delivered.submit(text) { latestValue(it) }
                }
            })
        }
    }
    DisposableEffect(view, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) view.resume()
            if (event == Lifecycle.Event.ON_PAUSE) view.pause()
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) view.resume()
        onDispose { lifecycle.removeObserver(observer); view.pause() }
    }
    AndroidView(factory = { view }, modifier = Modifier.fillMaxWidth().height(260.dp))
}
