package org.mhxxtools.mhxxrngtool.ui.capture

import androidx.camera.camera2.interop.ExperimentalCamera2Interop

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.util.Range
import android.util.Size
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.CameraInfo
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

data class CameraOption(
    val id: String,
    val label: String,
    val isExternal: Boolean,
    val facing: Int
)

data class CaptureUiState(
    val isRunning: Boolean = false,
    val status: String = "ANYOYO等をUSB OTGで接続 → 再接続",
    val fps: Float = 0f,
    val resolution: String = "",
    val cameraLabel: String = "",
    val error: String? = null,
    val hasFrame: Boolean = false,
    val cameras: List<CameraOption> = emptyList(),
    val selectedCameraId: String? = null
)

/**
 * USBキャプチャボード (ANYOYO 等 UVC) を CameraX で受信。
 * nExt Camera と同様に外部カメラを列挙して選択する。
 *
 * 接続例（写真と同じ）:
 *   Switch(ドック HDMI) → ANYOYO HDMI-IN → USB → スマホ(OTG)
 */
@OptIn(ExperimentalCamera2Interop::class)
class CaptureViewModel : ViewModel() {

    private val _state = MutableStateFlow(CaptureUiState())
    val state: StateFlow<CaptureUiState> = _state.asStateFlow()

    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val latestJpeg = AtomicReference<ByteArray?>(null)
    private val frameCount = AtomicInteger(0)
    private val fpsWindowStart = AtomicLong(System.currentTimeMillis())
    private var cameraProvider: ProcessCameraProvider? = null
    private var boundPreviewView: PreviewView? = null
    private var boundLifecycle: LifecycleOwner? = null
    private var preferredCameraId: String? = null

    fun bind(
        context: Context,
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView
    ) {
        boundPreviewView = previewView
        boundLifecycle = lifecycleOwner
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider
                val options = listCameras(provider)
                val selectedId = preferredCameraId
                    ?: options.firstOrNull { it.isExternal }?.id
                    ?: options.lastOrNull()?.id
                preferredCameraId = selectedId
                _state.update {
                    it.copy(
                        cameras = options,
                        selectedCameraId = selectedId,
                        error = if (options.none { it.isExternal } && options.isNotEmpty())
                            "外部カメラ未検出。内蔵カメラのみ。OTG接続と給電を確認"
                        else null
                    )
                }
                startCamera(lifecycleOwner, previewView, provider, selectedId)
            } catch (e: Exception) {
                _state.update {
                    it.copy(error = "カメラ初期化失敗: ${e.message}", isRunning = false)
                }
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun selectCamera(cameraId: String) {
        preferredCameraId = cameraId
        val provider = cameraProvider ?: return
        val preview = boundPreviewView ?: return
        val life = boundLifecycle ?: return
        startCamera(life, preview, provider, cameraId)
        _state.update { it.copy(selectedCameraId = cameraId) }
    }

    fun unbind() {
        cameraProvider?.unbindAll()
        _state.update { it.copy(isRunning = false, status = "停止中", fps = 0f) }
    }

    fun snapshotJpeg(): ByteArray? = latestJpeg.get()

    fun snapshotBitmap(): Bitmap? {
        val jpeg = latestJpeg.get() ?: return null
        return android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
    }

    private fun listCameras(provider: ProcessCameraProvider): List<CameraOption> {
        val list = mutableListOf<CameraOption>()
        for (info in provider.availableCameraInfos) {
            try {
                val c2 = Camera2CameraInfo.from(info)
                val id = c2.cameraId
                val facing = c2.getCameraCharacteristic(CameraCharacteristics.LENS_FACING)
                    ?: CameraCharacteristics.LENS_FACING_EXTERNAL
                val isExt = facing == CameraCharacteristics.LENS_FACING_EXTERNAL
                val facingName = when (facing) {
                    CameraCharacteristics.LENS_FACING_FRONT -> "前面"
                    CameraCharacteristics.LENS_FACING_BACK -> "背面"
                    CameraCharacteristics.LENS_FACING_EXTERNAL -> "外部(UVC)"
                    else -> "不明"
                }
                // 解像度ヒント
                val map = c2.getCameraCharacteristic(
                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
                )
                val sizes = map?.getOutputSizes(ImageFormat.YUV_420_888)
                    ?.maxByOrNull { it.width * it.height }
                val res = sizes?.let { "${it.width}x${it.height}" } ?: ""
                list += CameraOption(
                    id = id,
                    label = "$facingName id=$id $res",
                    isExternal = isExt,
                    facing = facing
                )
            } catch (_: Exception) {
                // skip
            }
        }
        // 外部を先頭に
        return list.sortedByDescending { it.isExternal }
    }

    private fun selectorForId(provider: ProcessCameraProvider, cameraId: String?): CameraSelector {
        if (cameraId != null) {
            val filtered = CameraSelector.Builder()
                .addCameraFilter { infos ->
                    infos.filter { info ->
                        try {
                            Camera2CameraInfo.from(info).cameraId == cameraId
                        } catch (_: Exception) {
                            false
                        }
                    }.ifEmpty { infos }
                }
                .build()
            if (provider.hasCamera(filtered)) return filtered
        }
        // 外部優先
        try {
            val external = CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_EXTERNAL)
                .build()
            if (provider.hasCamera(external)) return external
        } catch (_: Exception) { }
        // 利用可能なカメラから「外部っぽい」ものを選ぶ
        val options = listCameras(provider)
        val prefer = options.firstOrNull { it.isExternal } ?: options.lastOrNull()
        if (prefer != null) {
            return CameraSelector.Builder()
                .addCameraFilter { infos ->
                    infos.filter {
                        try {
                            Camera2CameraInfo.from(it).cameraId == prefer.id
                        } catch (_: Exception) {
                            false
                        }
                    }.ifEmpty { infos }
                }
                .build()
        }
        return CameraSelector.DEFAULT_BACK_CAMERA
    }

    private fun startCamera(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        provider: ProcessCameraProvider,
        cameraId: String?
    ) {
        provider.unbindAll()
        frameCount.set(0)
        fpsWindowStart.set(System.currentTimeMillis())
        latestJpeg.set(null)

        val selector = selectorForId(provider, cameraId)
        val label = try {
            val info = selector.filter(provider.availableCameraInfos).firstOrNull()
            if (info != null) {
                val id = Camera2CameraInfo.from(info).cameraId
                val facing = Camera2CameraInfo.from(info)
                    .getCameraCharacteristic(CameraCharacteristics.LENS_FACING)
                val name = when (facing) {
                    CameraCharacteristics.LENS_FACING_EXTERNAL -> "外部UVC"
                    CameraCharacteristics.LENS_FACING_BACK -> "背面"
                    CameraCharacteristics.LENS_FACING_FRONT -> "前面"
                    else -> "?"
                }
                "$name ($id)"
            } else "camera"
        } catch (_: Exception) {
            "camera"
        }

        val resolutionSelector = ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(
                    Size(1280, 720),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                )
            )
            .build()

        val previewBuilder = Preview.Builder().setResolutionSelector(resolutionSelector)
        trySetFps(previewBuilder)
        val preview = previewBuilder.build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }

        val analysisBuilder = ImageAnalysis.Builder()
            .setResolutionSelector(resolutionSelector)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
        trySetFps(analysisBuilder)
        val analysis = analysisBuilder.build()
        analysis.setAnalyzer(analysisExecutor) { image -> onFrame(image) }

        try {
            provider.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
            _state.update {
                it.copy(
                    isRunning = true,
                    status = "キャプチャ中（nExt Camera と同じ外部カメラ経路）",
                    cameraLabel = label,
                    error = null,
                    hasFrame = false
                )
            }
        } catch (e: Exception) {
            try {
                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis
                )
                _state.update {
                    it.copy(
                        isRunning = true,
                        status = "内蔵カメラでプレビュー中",
                        cameraLabel = "back",
                        error = "外部UVCにバインド失敗: ${e.message}\n" +
                            "nExt Cameraで映る場合は下のカメラ一覧から外部を選んでください"
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

    private fun trySetFps(builder: Any) {
        fun apply(ext: Camera2Interop.Extender<*>, range: Range<Int>) {
            try {
                ext.setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, range)
            } catch (_: Exception) { }
        }
        try {
            when (builder) {
                is Preview.Builder -> {
                    val ext = Camera2Interop.Extender(builder)
                    apply(ext, Range(60, 60))
                }
                is ImageAnalysis.Builder -> {
                    val ext = Camera2Interop.Extender(builder)
                    apply(ext, Range(60, 60))
                }
            }
        } catch (_: Exception) {
            try {
                when (builder) {
                    is Preview.Builder ->
                        Camera2Interop.Extender(builder)
                            .setCaptureRequestOption(
                                CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                                Range(30, 60)
                            )
                    is ImageAnalysis.Builder ->
                        Camera2Interop.Extender(builder)
                            .setCaptureRequestOption(
                                CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                                Range(30, 60)
                            )
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
            val now = System.currentTimeMillis()
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
                        status = "キャプチャ中 ${"%.1f".format(fps)} fps"
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
}
