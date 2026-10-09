package org.mhxxtools.mhxxrngtool.ui.capture

import androidx.camera.camera2.interop.ExperimentalCamera2Interop

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Size
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

data class CaptureUiState(
    val isRunning: Boolean = false,
    val status: String = "キャプチャボードをUSB OTGで接続してください",
    val fps: Float = 0f,
    val resolution: String = "",
    val cameraLabel: String = "",
    val error: String? = null,
    val hasFrame: Boolean = false
)

/**
 * USB キャプチャボード (UVC) を CameraX で受信。
 * 可能なら 1280×720 / 60fps を要求する。
 *
 * 接続例:
 *   Switch(ドック HDMI) → キャプチャボード HDMI-IN → USB → スマホ (OTG)
 */
@OptIn(ExperimentalCamera2Interop::class)
class CaptureViewModel : ViewModel() {

    private val _state = MutableStateFlow(CaptureUiState())
    val state: StateFlow<CaptureUiState> = _state.asStateFlow()

    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val latestJpeg = AtomicReference<ByteArray?>(null)
    private val frameCount = AtomicInteger(0)
    private val fpsWindowStart = AtomicLong(SystemClockMs())
    private var cameraProvider: ProcessCameraProvider? = null

    fun bind(
        context: Context,
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView
    ) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider
                startCamera(context, lifecycleOwner, previewView, provider)
            } catch (e: Exception) {
                _state.update {
                    it.copy(error = "カメラ初期化失敗: ${e.message}", isRunning = false)
                }
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun unbind() {
        cameraProvider?.unbindAll()
        _state.update { it.copy(isRunning = false, status = "停止中", fps = 0f) }
    }

    /** 最新フレームを JPEG バイトで取得（鑑定へ渡す用） */
    fun snapshotJpeg(): ByteArray? = latestJpeg.get()

    /** 最新フレームを Bitmap 化 */
    fun snapshotBitmap(): Bitmap? {
        val jpeg = latestJpeg.get() ?: return null
        return android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
    }

    private fun startCamera(
        context: Context,
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        provider: ProcessCameraProvider
    ) {
        provider.unbindAll()

        val selector = pickSelector(provider)
        val label = describeSelector(provider, selector)

        val resolutionSelector = ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(
                    Size(1280, 720),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                )
            )
            .build()

        // Preview
        val previewBuilder = Preview.Builder()
            .setResolutionSelector(resolutionSelector)
        trySetFps(previewBuilder)
        val preview = previewBuilder.build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }

        // Analysis @ high rate
        val analysisBuilder = ImageAnalysis.Builder()
            .setResolutionSelector(resolutionSelector)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
        trySetFps(analysisBuilder)
        val analysis = analysisBuilder.build()
        analysis.setAnalyzer(analysisExecutor) { image ->
            onFrame(image)
        }

        try {
            provider.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
            _state.update {
                it.copy(
                    isRunning = true,
                    status = "キャプチャ中（最大60fps要求）",
                    cameraLabel = label,
                    error = null
                )
            }
        } catch (e: Exception) {
            // EXTERNAL が無い端末 → 全カメラ再試行
            try {
                provider.unbindAll()
                val fallback = CameraSelector.DEFAULT_BACK_CAMERA
                provider.bindToLifecycle(lifecycleOwner, fallback, preview, analysis)
                _state.update {
                    it.copy(
                        isRunning = true,
                        status = "内蔵カメラでプレビュー中（外部UVC未検出）",
                        cameraLabel = "back",
                        error = "外部キャプチャ未検出。USB OTG接続とボード給電を確認してください。"
                    )
                }
            } catch (e2: Exception) {
                _state.update {
                    it.copy(
                        isRunning = false,
                        error = "カメラ起動失敗: ${e2.message}",
                        status = "エラー"
                    )
                }
            }
        }
    }

    private fun pickSelector(provider: ProcessCameraProvider): CameraSelector {
        // 1) 外部カメラ優先 (USB UVC)
        try {
            val external = CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_EXTERNAL)
                .build()
            if (provider.hasCamera(external)) return external
        } catch (_: Exception) { /* API差 */ }

        // 2) Camera2 で LENS_FACING_EXTERNAL を探す
        try {
            val filtered = CameraSelector.Builder()
                .addCameraFilter { infos ->
                    val ext = infos.filter { info ->
                        try {
                            val id = Camera2CameraInfo.from(info).cameraId
                            val chars = Camera2CameraInfo.from(info)
                                .getCameraCharacteristic(CameraCharacteristics.LENS_FACING)
                            chars == CameraCharacteristics.LENS_FACING_EXTERNAL
                        } catch (_: Exception) {
                            false
                        }
                    }
                    if (ext.isNotEmpty()) ext else infos
                }
                .build()
            if (provider.hasCamera(filtered)) return filtered
        } catch (_: Exception) { }

        return CameraSelector.DEFAULT_BACK_CAMERA
    }

    private fun describeSelector(
        provider: ProcessCameraProvider,
        selector: CameraSelector
    ): String {
        return try {
            val info = selector.filter(provider.availableCameraInfos).firstOrNull()
                ?: return "unknown"
            val id = Camera2CameraInfo.from(info).cameraId
            val facing = Camera2CameraInfo.from(info)
                .getCameraCharacteristic(CameraCharacteristics.LENS_FACING)
            "id=$id facing=$facing"
        } catch (_: Exception) {
            "camera"
        }
    }

    private fun trySetFps(builder: Any) {
        try {
            when (builder) {
                is Preview.Builder -> {
                    val ext = Camera2Interop.Extender(builder)
                    ext.setCaptureRequestOption(
                        CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                        android.util.Range(60, 60)
                    )
                }
                is ImageAnalysis.Builder -> {
                    val ext = Camera2Interop.Extender(builder)
                    ext.setCaptureRequestOption(
                        CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                        android.util.Range(60, 60)
                    )
                }
            }
        } catch (_: Exception) {
            // 60fps 非対応デバイスはデフォルトに任せる
            try {
                when (builder) {
                    is Preview.Builder -> {
                        Camera2Interop.Extender(builder).setCaptureRequestOption(
                            CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                            android.util.Range(30, 60)
                        )
                    }
                    is ImageAnalysis.Builder -> {
                        Camera2Interop.Extender(builder).setCaptureRequestOption(
                            CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                            android.util.Range(30, 60)
                        )
                    }
                }
            } catch (_: Exception) { }
        }
    }

    private fun onFrame(image: ImageProxy) {
        try {
            val w = image.width
            val h = image.height
            val jpeg = yuv420ToJpeg(image) ?: return
            latestJpeg.set(jpeg)

            val n = frameCount.incrementAndGet()
            val now = SystemClockMs()
            val start = fpsWindowStart.get()
            val elapsed = now - start
            if (elapsed >= 1000L) {
                val fps = n * 1000f / elapsed
                frameCount.set(0)
                fpsWindowStart.set(now)
                _state.update {
                    it.copy(
                        fps = fps,
                        resolution = "${w}x${h}",
                        hasFrame = true,
                        status = if (fps >= 55f) "キャプチャ中 ${"%.0f".format(fps)} fps"
                        else "キャプチャ中 ${"%.1f".format(fps)} fps（60に満たない場合あり）"
                    )
                }
            }
        } finally {
            image.close()
        }
    }

    private fun yuv420ToJpeg(image: ImageProxy): ByteArray? {
        return try {
            val yBuffer = image.planes[0].buffer
            val uBuffer = image.planes[1].buffer
            val vBuffer = image.planes[2].buffer
            val ySize = yBuffer.remaining()
            val uSize = uBuffer.remaining()
            val vSize = vBuffer.remaining()
            val nv21 = ByteArray(ySize + uSize + vSize)
            yBuffer.get(nv21, 0, ySize)
            // NV21 = Y + V + U
            vBuffer.get(nv21, ySize, vSize)
            uBuffer.get(nv21, ySize + vSize, uSize)
            val yuv = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
            val out = ByteArrayOutputStream()
            yuv.compressToJpeg(Rect(0, 0, image.width, image.height), 90, out)
            out.toByteArray()
        } catch (_: Exception) {
            null
        }
    }

    override fun onCleared() {
        super.onCleared()
        analysisExecutor.shutdown()
        cameraProvider?.unbindAll()
    }

    private fun SystemClockMs(): Long = System.currentTimeMillis()
}
