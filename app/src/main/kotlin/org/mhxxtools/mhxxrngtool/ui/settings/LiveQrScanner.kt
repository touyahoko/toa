package org.mhxxtools.mhxxrngtool.ui.settings

import android.os.Handler
import android.os.Looper
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * ライブカメラで QR を連続検出。検出ごとに [onQr]（メインスレッド）。
 */
@Composable
fun LiveQrScanner(
    hint: String,
    onQr: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val scanner = remember {
        val opts = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
        BarcodeScanning.getClient(opts)
    }
    val lastEmitted = remember { AtomicReference("" to 0L) }

    DisposableEffect(Unit) {
        onDispose {
            try { executor.shutdown() } catch (_: Exception) {}
            try { scanner.close() } catch (_: Exception) {}
        }
    }

    Box(modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                }
                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                cameraProviderFuture.addListener({
                    try {
                        val provider = cameraProviderFuture.get()
                        val preview = Preview.Builder().build().also { p ->
                            p.setSurfaceProvider(previewView.surfaceProvider)
                        }
                        @Suppress("DEPRECATION")
                        val analysis = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .setTargetResolution(Size(1920, 1080))
                            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                            .build()

                        analysis.setAnalyzer(executor) { imageProxy ->
                            val mediaImage = imageProxy.image
                            if (mediaImage == null) {
                                imageProxy.close()
                                return@setAnalyzer
                            }
                            val image = InputImage.fromMediaImage(
                                mediaImage,
                                imageProxy.imageInfo.rotationDegrees
                            )
                            scanner.process(image)
                                .addOnSuccessListener { codes ->
                                    val raw = codes
                                        .asSequence()
                                        .mapNotNull { it.rawValue?.trim() }
                                        .firstOrNull { it.isNotEmpty() }
                                        ?: return@addOnSuccessListener
                                    val now = System.currentTimeMillis()
                                    val (prev, t0) = lastEmitted.get()
                                    // 同じ内容は 2 秒以内は無視。違う内容は即通知
                                    if (raw == prev && now - t0 < 2000L) return@addOnSuccessListener
                                    lastEmitted.set(raw to now)
                                    mainHandler.post { onQr(raw) }
                                }
                                .addOnFailureListener { /* ignore frame errors */ }
                                .addOnCompleteListener { imageProxy.close() }
                        }

                        provider.unbindAll()
                        provider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            analysis
                        )
                    } catch (_: Exception) {
                        // camera bind fail
                    }
                }, ContextCompat.getMainExecutor(ctx))
                previewView
            },
            modifier = Modifier.fillMaxSize()
        )

        Box(
            Modifier
                .align(Alignment.Center)
                .size(260.dp)
                .border(3.dp, Color(0xFF348FF9), RoundedCornerShape(16.dp))
        )

        Column(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .background(Color(0xCC090C13))
                .padding(16.dp)
        ) {
            Text(
                "QRを枠に合わせてください（自動読取）",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
            Text(
                hint,
                color = Color(0xFF9DA5B1),
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 4.dp)
            )
            Text(
                "画面のQR全体が枠に入るように近づけると読みやすくなります",
                color = Color(0xFF9DA5B1),
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        TextButton(
            onClick = onClose,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(24.dp)
        ) {
            Text("閉じる", color = Color.White, fontSize = 16.sp)
        }
    }
}
