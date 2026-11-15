package de.kontrakurs.kontraprompter

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.ConnectivityManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
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
    val available = remember(addresses, port, token) {
        addresses.distinct().filter { PairingQr.encode(it, port, token, System.currentTimeMillis()) != null }
    }
    val direct = remember(available) {
        runCatching {
            java.net.NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && (it.name == "p2p0" || it.name.startsWith("p2p-")) }
                .flatMap { it.inetAddresses.toList() }.mapNotNull { it.hostAddress }
                .firstOrNull { it in available }
        }.getOrNull()
    }
    val preferred = remember(available) {
        runCatching {
            val connectivity = context.getSystemService(ConnectivityManager::class.java)
            connectivity.getLinkProperties(connectivity.activeNetwork)?.linkAddresses.orEmpty()
                .mapNotNull { it.address.hostAddress }.firstOrNull { it in available }
        }.getOrNull()
    }
    var selected by remember(available, direct, preferred) { mutableStateOf(direct ?: preferred ?: available.firstOrNull()) }
    val payload = remember(selected, port, token, now) { selected?.let { PairingQr.encode(it, port, token, now) } }
    if (available.size > 1) {
        Text(stringResource(R.string.pair_qr_network), style = MaterialTheme.typography.labelLarge)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            available.forEach { address ->
                FilterChip(selected = address == selected, onClick = { selected = address },
                    label = { Text(if (address == direct) stringResource(R.string.pair_qr_direct_address, address) else address) })
            }
        }
    }
    val bitmap = remember(payload) { payload?.let { content -> runCatching {
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 512, 512, mapOf(EncodeHintType.MARGIN to 4))
        val pixels = IntArray(512 * 512) { index -> if (matrix[index % 512, index / 512]) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
        Bitmap.createBitmap(pixels, 512, 512, Bitmap.Config.ARGB_8888).asImageBitmap()
    }.getOrNull() } }
    if (bitmap != null) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Image(bitmap, contentDescription = stringResource(R.string.pair_qr_description), modifier = Modifier.sizeIn(maxWidth = 240.dp, maxHeight = 240.dp).fillMaxWidth().aspectRatio(1f).background(Color.White))
        }
        Text(stringResource(R.string.pair_qr_scan_instruction), color = SoftText, style = MaterialTheme.typography.bodySmall)
    } else Text(stringResource(R.string.pair_qr_unavailable), color = SoftText, style = MaterialTheme.typography.bodySmall)
}

@Composable
internal fun PairingQrScanner(onPair: (QrPairing) -> Unit) {
    val context = LocalContext.current
    val deniedMessage = stringResource(R.string.pair_qr_camera_denied)
    val invalidMessage = stringResource(R.string.pair_qr_invalid)
    var scanning by remember { mutableStateOf(false) }
    var permitted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var error by remember { mutableStateOf<String?>(null) }
    val requestCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permitted = granted
        scanning = granted
        if (!granted) error = deniedMessage
    }
    if (!scanning) {
        MainAction(stringResource(R.string.pair_qr_scan)) {
            error = null
            permitted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            if (permitted) scanning = true else requestCamera.launch(Manifest.permission.CAMERA)
        }
    } else {
        Text(stringResource(R.string.pair_qr_aim), color = SoftText, style = MaterialTheme.typography.bodyMedium)
        QrCameraPreview(onValue = { value ->
            val decoded = PairingQr.decode(value, System.currentTimeMillis())
            if (decoded == null) { error = invalidMessage; false }
            else { scanning = false; error = null; onPair(decoded); true }
        })
        TextButton(onClick = { scanning = false }) { Text(stringResource(R.string.pair_qr_stop)) }
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
