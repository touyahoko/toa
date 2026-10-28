package org.mhxxtools.mhxxrngtool.ui.settings

import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
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
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * ライブカメラで QR を連続検出。検出ごとに [onQr] を呼ぶ（重複は短時間抑制）。
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
    val executor = remember { Executors.newSingleThreadExecutor() }
    val scanner = remember { BarcodeScanning.getClient() }
    val busy = remember { AtomicBoolean(false) }
    val lastValue = remember { AtomicLong(0) }
    val lastText = remember { java.util.concurrent.atomic.AtomicReference("") }

    DisposableEffect(Unit) {
        onDispose {
            executor.shutdown()
            scanner.close()
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
                    val provider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setResolutionSelector(
                            ResolutionSelector.Builder()
                                .setResolutionStrategy(
                                    ResolutionStrategy(
                                        Size(1280, 720),
                                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                                    )
                                )
                                .build()
                        )
                        .build()
                    analysis.setAnalyzer(executor) { imageProxy ->
                        try {
                            val media = imageProxy.image
                            if (media != null && !busy.get()) {
                                val image = InputImage.fromMediaImage(
                                    media,
                                    imageProxy.imageInfo.rotationDegrees
                                )
                                scanner.process(image)
                                    .addOnSuccessListener { codes ->
                                        val raw = codes
                                            .asSequence()
                                            .filter {
                                                it.format == Barcode.FORMAT_QR_CODE ||
                                                    it.format == Barcode.FORMAT_ALL_FORMATS ||
                                                    it.rawValue != null
                                            }
                                            .mapNotNull { it.rawValue }
                                            .firstOrNull { it.isNotBlank() }
                                        if (raw != null) {
                                            val now = System.currentTimeMillis()
                                            val same = raw == lastText.get() && now - lastValue.get() < 2500
                                            if (!same && busy.compareAndSet(false, true)) {
                                                lastText.set(raw)
                                                lastValue.set(now)
                                                onQr(raw)
                                                // 次のQR（2つ目）を読めるよう少し後に解除
                                                executor.execute {
                                                    try { Thread.sleep(1200) } catch (_: Exception) {}
                                                    busy.set(false)
                                                }
                                            }
                                        }
                                    }
                                    .addOnCompleteListener { imageProxy.close() }
                            } else {
                                imageProxy.close()
                            }
                        } catch (_: Exception) {
                            imageProxy.close()
                        }
                    }
                    try {
                        provider.unbindAll()
                        provider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            analysis
                        )
                    } catch (_: Exception) {}
                }, ContextCompat.getMainExecutor(ctx))
                previewView
            },
            modifier = Modifier.fillMaxSize()
        )

        // ガイド枠
        Box(
            Modifier
                .align(Alignment.Center)
                .size(240.dp)
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
                "QRを枠に合わせてください",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
            Text(hint, color = Color(0xFF9DA5B1), fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
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
