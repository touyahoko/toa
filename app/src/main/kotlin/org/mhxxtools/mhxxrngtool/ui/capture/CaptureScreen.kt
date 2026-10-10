package org.mhxxtools.mhxxrngtool.ui.capture

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Environment
import android.view.TextureView
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.jiangdg.ausbc.CameraClient
import com.jiangdg.ausbc.callback.ICaptureCallBack
import com.jiangdg.ausbc.camera.CameraUvcStrategy
import com.jiangdg.ausbc.camera.bean.CameraRequest
import com.jiangdg.ausbc.render.env.RotateType
import com.jiangdg.ausbc.widget.AspectRatioTextureView
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

private const val PREVIEW_W = 1280
private const val PREVIEW_H = 720

private val FPS_OPTIONS = listOf(30, 60, 90, 120)

private fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

/**
 * USBキャプチャ全画面
 * - Switch 16:9 を画面いっぱいに収める
 * - フレーム連写生成（指定FPS）
 * - ゲーム音声（48kHz ステレオ再生）
 */
@Composable
fun CaptureScreen(
    onSnapshotFile: (File) -> Unit = {},
    onBack: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val scope = rememberCoroutineScope()

    var status by remember { mutableStateOf("初期化中…") }
    var error by remember { mutableStateOf<String?>(null) }
    val clientHolder = remember { arrayOfNulls<CameraClient>(1) }
    val textureHolder = remember { arrayOfNulls<AspectRatioTextureView>(1) }

    var connected by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf(false) }
    var frameCount by remember { mutableStateOf(0) }
    var targetFps by remember { mutableIntStateOf(60) }
    var generating by remember { mutableStateOf(false) }
    var genCount by remember { mutableIntStateOf(0) }
    var audioOn by remember { mutableStateOf(false) }
    var showControls by remember { mutableStateOf(true) }

    val audioRunning = remember { AtomicBoolean(false) }
    var audioJob by remember { mutableStateOf<Job?>(null) }
    var genJob by remember { mutableStateOf<Job?>(null) }

    DisposableEffect(Unit) {
        val prev = activity?.requestedOrientation
            ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose {
            audioRunning.set(false)
            audioJob?.cancel()
            genJob?.cancel()
            activity?.requestedOrientation = prev
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val cam = result[Manifest.permission.CAMERA] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (cam) status = "USBカメラ接続待ち…"
        else {
            error = "CAMERA権限が必要です"
            status = "権限エラー"
        }
    }

    LaunchedEffect(Unit) {
        val need = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) need += Manifest.permission.CAMERA
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) need += Manifest.permission.RECORD_AUDIO
        if (need.isNotEmpty()) permissionLauncher.launch(need.toTypedArray())
        else status = "USBカメラ接続待ち…"
    }

    LaunchedEffect(frameCount) {
        if (frameCount > 2 && !connected) {
            connected = true
            error = null
            status = "接続済み"
        }
    }

    fun startHqAudio() {
        if (audioRunning.get()) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            error = "音声にはRECORD_AUDIO権限が必要です"
            return
        }
        audioJob?.cancel()
        audioJob = scope.launch(Dispatchers.IO) {
            audioRunning.set(true)
            // 48kHz ステレオ優先 → 失敗時は 44.1kHz → モノラル
            val configs = listOf(
                Triple(48000, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.CHANNEL_OUT_STEREO),
                Triple(44100, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.CHANNEL_OUT_STEREO),
                Triple(48000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.CHANNEL_OUT_MONO),
                Triple(44100, AudioFormat.CHANNEL_IN_MONO, AudioFormat.CHANNEL_OUT_MONO)
            )
            var record: AudioRecord? = null
            var track: AudioTrack? = null
            var rate = 48000
            var outCh = AudioFormat.CHANNEL_OUT_STEREO
            var bufSize = 0
            for ((sr, inCh, oCh) in configs) {
                val min = AudioRecord.getMinBufferSize(sr, inCh, AudioFormat.ENCODING_PCM_16BIT)
                if (min <= 0) continue
                val size = min * 4
                val r = try {
                    AudioRecord(
                        MediaRecorder.AudioSource.UNPROCESSED,
                        sr, inCh, AudioFormat.ENCODING_PCM_16BIT, size
                    )
                } catch (_: Exception) {
                    try {
                        AudioRecord(
                            MediaRecorder.AudioSource.DEFAULT,
                            sr, inCh, AudioFormat.ENCODING_PCM_16BIT, size
                        )
                    } catch (_: Exception) {
                        null
                    }
                }
                if (r != null && r.state == AudioRecord.STATE_INITIALIZED) {
                    record = r
                    rate = sr
                    outCh = oCh
                    bufSize = size
                    break
                } else {
                    r?.release()
                }
            }
            if (record == null) {
                withContext(Dispatchers.Main) {
                    audioOn = false
                    error = "高音質音声の初期化に失敗（USB音声が認識されていない可能性）"
                }
                audioRunning.set(false)
                return@launch
            }
            val minOut = AudioTrack.getMinBufferSize(rate, outCh, AudioFormat.ENCODING_PCM_16BIT)
            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(rate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(outCh)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minOut, bufSize))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            try {
                record.startRecording()
                track.play()
                withContext(Dispatchers.Main) {
                    audioOn = true
                    status = "接続済み / 音声 ${rate / 1000}kHz"
                }
                val buf = ByteArray(bufSize)
                while (audioRunning.get() && isActive) {
                    val n = record.read(buf, 0, buf.size)
                    if (n > 0) track.write(buf, 0, n)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    audioOn = false
                    error = "音声エラー: ${e.message}"
                }
            } finally {
                try { record.stop() } catch (_: Exception) {}
                try { record.release() } catch (_: Exception) {}
                try { track.stop() } catch (_: Exception) {}
                try { track.release() } catch (_: Exception) {}
                audioRunning.set(false)
                withContext(Dispatchers.Main) { audioOn = false }
            }
        }
    }

    fun stopHqAudio() {
        audioRunning.set(false)
        audioJob?.cancel()
        audioJob = null
        audioOn = false
    }

    fun startFrameGeneration() {
        if (generating) return
        val tv = textureHolder[0] ?: run {
            error = "プレビュー未準備"
            return
        }
        generating = true
        genCount = 0
        val dir = File(
            context.getExternalFilesDir(Environment.DIRECTORY_PICTURES),
            "frames_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}_${targetFps}fps"
        ).also { it.mkdirs() }
        status = "フレーム生成中 ${targetFps}fps → ${dir.name}"
        val intervalMs = (1000.0 / targetFps).toLong().coerceAtLeast(1)
        genJob = scope.launch(Dispatchers.Default) {
            var idx = 0
            while (isActive && generating) {
                val start = System.nanoTime()
                try {
                    val bmp = withContext(Dispatchers.Main) {
                        tv.bitmap
                    }
                    if (bmp != null && !bmp.isRecycled) {
                        val out = File(dir, "frame_%06d.jpg".format(idx))
                        FileOutputStream(out).use { fos ->
                            bmp.compress(Bitmap.CompressFormat.JPEG, 92, fos)
                        }
                        if (bmp !== tv.bitmap) bmp.recycle()
                        idx++
                        withContext(Dispatchers.Main) { genCount = idx }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) { error = "生成: ${e.message}" }
                }
                val elapsed = (System.nanoTime() - start) / 1_000_000
                delay((intervalMs - elapsed).coerceAtLeast(1))
            }
            withContext(Dispatchers.Main) {
                generating = false
                status = "接続済み（${genCount}枚保存: ${dir.name}）"
            }
        }
    }

    fun stopFrameGeneration() {
        generating = false
        genJob?.cancel()
        genJob = null
    }

    Box(
        modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // ===== 全画面プレビュー（16:9 を画面最大で中央配置） =====
        AndroidView(
            factory = { ctx ->
                val root = FrameLayout(ctx).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    setBackgroundColor(0xFF000000.toInt())
                }
                val tv = AspectRatioTextureView(ctx).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.Gravity.CENTER
                    )
                }
                textureHolder[0] = tv
                root.addView(tv)
                try {
                    val client = CameraClient.newBuilder(ctx)
                        .setEnableGLES(true)
                        .setRawImage(false)
                        .setCameraStrategy(CameraUvcStrategy(ctx))
                        .setCameraRequest(
                            CameraRequest.Builder()
                                .setFrontCamera(false)
                                .setPreviewWidth(PREVIEW_W)
                                .setPreviewHeight(PREVIEW_H)
                                .create()
                        )
                        .setDefaultRotateType(RotateType.ANGLE_0)
                        .openDebug(false)
                        .build()
                    clientHolder[0] = client
                    tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(
                            surface: SurfaceTexture, width: Int, height: Int
                        ) {
                            try {
                                tv.setAspectRatio(PREVIEW_W, PREVIEW_H)
                                client.openCamera(tv)
                                status = "プレビュー要求済み（USB許可を確認）"
                                startHqAudio()
                            } catch (e: Exception) {
                                error = "openCamera: ${e.message}"
                                status = "エラー"
                            }
                        }

                        override fun onSurfaceTextureSizeChanged(
                            surface: SurfaceTexture, width: Int, height: Int
                        ) {
                            tv.setAspectRatio(PREVIEW_W, PREVIEW_H)
                        }

                        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                            stopFrameGeneration()
                            stopHqAudio()
                            try {
                                if (recording) {
                                    try { client.captureVideoStop() } catch (_: Exception) {}
                                    recording = false
                                }
                                client.closeCamera()
                            } catch (_: Exception) {
                            }
                            connected = false
                            return true
                        }

                        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
                            frameCount++
                            if (client.isCameraOpened() == true && !connected) {
                                connected = true
                                error = null
                                status = if (audioOn) "接続済み / 音声ON" else "接続済み"
                            }
                        }
                    }
                } catch (e: Exception) {
                    error = "初期化失敗: ${e.message}"
                    status = "エラー"
                }
                root
            },
            modifier = Modifier
                .fillMaxSize()
                // 上下UIの上に映像を最大表示
                .padding(0.dp)
        )

        // タップでUI表示切替（余白を広く取る）
        Box(
            Modifier
                .fillMaxSize()
                .padding(top = 48.dp, bottom = 72.dp)
        ) {
            // 透明ヒット領域
            androidx.compose.foundation.layout.Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Transparent)
            )
        }

        if (showControls) {
            // ===== 上部 =====
            Column(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.65f))
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    IconButton(
                        onClick = {
                            stopFrameGeneration()
                            stopHqAudio()
                            onBack()
                        },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(Icons.Default.Close, "閉じる", tint = Color.White)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (connected) "● 接続済み" else status,
                            color = when {
                                error != null -> Color(0xFFFF8A80)
                                connected -> Color(0xFF69F0AE)
                                else -> Color(0xFFB0BEC5)
                            },
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        if (error != null) {
                            Text(error!!, color = Color(0xFFFF8A80), fontSize = 10.sp, maxLines = 1)
                        } else if (generating) {
                            Text("フレーム生成 ${genCount}枚 @ ${targetFps}fps", color = Color.Yellow, fontSize = 10.sp)
                        } else if (audioOn) {
                            Text("音声ON（高音質）", color = Color(0xFF81D4FA), fontSize = 10.sp)
                        }
                    }
                    if (recording) {
                        Surface(color = Color.Red, shape = RoundedCornerShape(4.dp)) {
                            Text(
                                "REC",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                    TextButton(
                        onClick = { showControls = false },
                        colors = ButtonDefaults.textButtonColors(contentColor = Color.White)
                    ) { Text("UI隠す", fontSize = 11.sp) }
                }
                // FPS 選択
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("FPS:", color = Color.White, fontSize = 11.sp)
                    FPS_OPTIONS.forEach { fps ->
                        FilterChip(
                            selected = targetFps == fps,
                            onClick = { targetFps = fps },
                            label = { Text("${fps}", fontSize = 11.sp) },
                            enabled = !generating
                        )
                    }
                }
            }

            // ===== 下部操作 =====
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.65f))
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = {
                        if (audioOn) stopHqAudio() else startHqAudio()
                    },
                    enabled = connected || clientHolder[0] != null,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    modifier = Modifier.weight(0.85f)
                ) { Text(if (audioOn) "音声OFF" else "音声ON", fontSize = 11.sp) }

                OutlinedButton(
                    onClick = {
                        if (generating) stopFrameGeneration() else startFrameGeneration()
                    },
                    enabled = connected,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = if (generating) Color.Yellow else Color.White
                    ),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    modifier = Modifier.weight(1.1f)
                ) {
                    Text(
                        if (generating) "生成停止" else "フレーム生成",
                        fontSize = 11.sp
                    )
                }

                OutlinedButton(
                    onClick = {
                        val c = clientHolder[0] ?: return@OutlinedButton
                        try {
                            if (recording) {
                                c.captureVideoStop()
                                recording = false
                                status = "接続済み（録画停止）"
                            } else {
                                val dir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
                                    ?: context.cacheDir
                                val out = File(dir, "uvc_rec_${System.currentTimeMillis()}.mp4")
                                c.captureVideoStart(object : ICaptureCallBack {
                                    override fun onBegin() {
                                        recording = true
                                        status = "録画中…"
                                    }

                                    override fun onError(err: String?) {
                                        recording = false
                                        error = err ?: "録画失敗"
                                    }

                                    override fun onComplete(path: String?) {
                                        recording = false
                                        status = "接続済み（録画完了）"
                                    }
                                }, out.absolutePath)
                            }
                        } catch (e: Exception) {
                            recording = false
                            error = e.message
                        }
                    },
                    enabled = connected || clientHolder[0] != null,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = if (recording) Color.Red else Color.White
                    ),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    modifier = Modifier.weight(0.85f)
                ) {
                    Icon(
                        if (recording) Icons.Default.Stop else Icons.Default.FiberManualRecord,
                        null,
                        Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(2.dp))
                    Text(if (recording) "停止" else "録画", fontSize = 11.sp)
                }

                Button(
                    onClick = {
                        val c = clientHolder[0] ?: return@Button
                        val out = File(context.cacheDir, "uvc_snap_${System.currentTimeMillis()}.jpg")
                        try {
                            c.captureImage(object : ICaptureCallBack {
                                override fun onBegin() { status = "撮影中…" }
                                override fun onError(err: String?) {
                                    error = err ?: "撮影失敗"
                                    status = "接続済み"
                                }
                                override fun onComplete(path: String?) {
                                    val f = path?.let { File(it) }
                                    if (f != null && f.exists()) {
                                        status = "接続済み（撮影完了）"
                                        onSnapshotFile(f)
                                    } else error = "ファイル未作成"
                                }
                            }, out.absolutePath)
                        } catch (e: Exception) {
                            error = e.message
                        }
                    },
                    enabled = connected || clientHolder[0] != null,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    modifier = Modifier.weight(1f)
                ) { Text("鑑定", fontSize = 11.sp) }
            }
        } else {
            // UI非表示時は端に復帰ボタンのみ
            TextButton(
                onClick = { showControls = true },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(8.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = Color.White)
            ) { Text("UI表示") }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            stopFrameGeneration()
            stopHqAudio()
            try {
                val c = clientHolder[0]
                if (recording) try { c?.captureVideoStop() } catch (_: Exception) {}
                c?.closeCamera()
            } catch (_: Exception) {
            }
            clientHolder[0] = null
        }
    }
}
