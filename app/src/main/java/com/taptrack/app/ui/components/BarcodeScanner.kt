package com.taptrack.app.ui.components

import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors

@androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
@Composable
fun BarcodeScanner(
    onBarcodeDetected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val previewView = remember { PreviewView(context) }
    val cameraRef = remember { mutableStateOf<Camera?>(null) }
    var torchOn by remember { mutableStateOf(false) }
    var scanned by remember { mutableStateOf(false) }

    val executor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) { onDispose { executor.shutdown() } }

    val scanner = remember {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(
                    Barcode.FORMAT_QR_CODE,
                    Barcode.FORMAT_CODE_128,
                    Barcode.FORMAT_CODE_39,
                    Barcode.FORMAT_CODE_93,
                    Barcode.FORMAT_DATA_MATRIX,
                    Barcode.FORMAT_EAN_13,
                    Barcode.FORMAT_EAN_8
                )
                .build()
        )
    }
    DisposableEffect(Unit) { onDispose { scanner.close() } }

    LaunchedEffect(previewView) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { ia ->
                    ia.setAnalyzer(executor) { proxy ->
                        val media = proxy.image
                        if (media != null && !scanned) {
                            val img = InputImage.fromMediaImage(
                                media,
                                proxy.imageInfo.rotationDegrees
                            )
                            scanner.process(img)
                                .addOnSuccessListener { barcodes ->
                                    val raw = barcodes.firstOrNull()?.rawValue
                                    if (!raw.isNullOrBlank() && !scanned) {
                                        scanned = true
                                        ContextCompat.getMainExecutor(context).execute {
                                            onBarcodeDetected(raw)
                                        }
                                    }
                                }
                                .addOnCompleteListener { proxy.close() }
                        } else {
                            proxy.close()
                        }
                    }
                }
            try {
                provider.unbindAll()
                cameraRef.value = provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, ContextCompat.getMainExecutor(context))
    }

    LaunchedEffect(torchOn) {
        cameraRef.value?.cameraControl?.enableTorch(torchOn)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            // ── Full-screen camera preview ───────────────────────────────
            AndroidView(
                factory = { previewView },
                modifier = Modifier.fillMaxSize()
            )

            // ── Vignette + corner bracket overlay ───────────────────────
            Canvas(modifier = Modifier.fillMaxSize()) {
                val hPad = size.width * 0.10f
                val fw = size.width - 2 * hPad
                val fh = fw * 0.65f
                val vPad = (size.height - fh) / 2f
                val fl = hPad
                val ft = vPad
                val fr = size.width - hPad
                val fb = vPad + fh
                val corner = 32.dp.toPx()
                val sw = 3.5.dp.toPx()

                val dim = Color.Black.copy(alpha = 0.60f)
                drawRect(dim, size = Size(size.width, ft))
                drawRect(dim, topLeft = Offset(0f, fb), size = Size(size.width, size.height - fb))
                drawRect(dim, topLeft = Offset(0f, ft), size = Size(fl, fh))
                drawRect(dim, topLeft = Offset(fr, ft), size = Size(size.width - fr, fh))

                val w = Color.White
                val cap = StrokeCap.Round
                drawLine(w, Offset(fl, ft + corner), Offset(fl, ft), sw, cap)
                drawLine(w, Offset(fl, ft), Offset(fl + corner, ft), sw, cap)
                drawLine(w, Offset(fr - corner, ft), Offset(fr, ft), sw, cap)
                drawLine(w, Offset(fr, ft), Offset(fr, ft + corner), sw, cap)
                drawLine(w, Offset(fl, fb - corner), Offset(fl, fb), sw, cap)
                drawLine(w, Offset(fl, fb), Offset(fl + corner, fb), sw, cap)
                drawLine(w, Offset(fr - corner, fb), Offset(fr, fb), sw, cap)
                drawLine(w, Offset(fr, fb), Offset(fr, fb - corner), sw, cap)
            }

            // ── Top header ──────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(start = 4.dp, end = 4.dp, top = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Close scanner",
                        tint = Color.White
                    )
                }
                Text(
                    text = "Scan Meter Code",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { torchOn = !torchOn }) {
                    Icon(
                        imageVector = if (torchOn) Icons.Default.FlashOn else Icons.Default.FlashOff,
                        contentDescription = if (torchOn) "Torch on" else "Torch off",
                        tint = if (torchOn) Color(0xFFFFD740) else Color.White
                    )
                }
            }

            // ── Bottom instruction ───────────────────────────────────────
            Text(
                text = "Point at the meter's QR code or barcode",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.85f),
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 48.dp, start = 24.dp, end = 24.dp)
            )
        }
    }
}
