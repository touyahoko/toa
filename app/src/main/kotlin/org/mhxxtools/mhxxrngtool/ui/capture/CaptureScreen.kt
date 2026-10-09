package org.mhxxtools.mhxxrngtool.ui.capture

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.os.Environment
import android.view.TextureView
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import com.jiangdg.ausbc.callback.IPlayCallBack
import com.jiangdg.ausbc.camera.CameraUvcStrategy
import com.jiangdg.ausbc.camera.bean.CameraRequest
import com.jiangdg.ausbc.render.env.RotateType
import com.jiangdg.ausbc.widget.AspectRatioTextureView
import java.io.File

private fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

/**
 * AUSBC USBキャプチャ
 * - 全画面 16:9（Switch画面を全て表示）
 * - ゲーム音（キャプチャデバイスの音声入力を再生）
 * - 横画面推奨
 */
@Composable
fun CaptureScreen(
    onSnapshotFile: (File) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    var status by remember { mutableStateOf("初期化中…") }
    var error by remember { mutableStateOf<String?>(null) }
    val clientHolder = remember { arrayOfNulls<CameraClient>(1) }
    var connected by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf(false) }
    var audioOn by remember { mutableStateOf(false) }
    var frameCount by remember { mutableStateOf(0) }

    // キャプチャ中は横画面固定
    DisposableEffect(Unit) {
        val prev = activity?.requestedOrientation
            ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose {
            try {
                clientHolder[0]?.stopPlayMic()
            } catch (_: Exception) {
            }
            activity?.requestedOrientation = prev
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val cam = result[Manifest.permission.CAMERA] != false
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED || cam
        ) {
            status = "USBカメラ接続待ち…"
        } else {
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
        if (need.isNotEmpty()) {
            permissionLauncher.launch(need.toTypedArray())
        } else {
            status = "USBカメラ接続待ち…"
        }
    }

    LaunchedEffect(frameCount) {
        if (frameCount > 2 && !connected) {
            connected = true
            error = null
            status = "接続済み"
        }
    }

    fun startGameAudio(client: CameraClient) {
        try {
            client.startPlayMic(object : IPlayCallBack {
                override fun onBegin() {
                    audioOn = true
                    status = "接続済み（音声ON）"
                }

                override fun onError(errorMsg: String) {
                    audioOn = false
                    // 音声失敗でも映像は継続
                    status = "接続済み（映像のみ）"
                }

                override fun onComplete() {
                    audioOn = false
                }
            })
        } catch (_: Exception) {
            audioOn = false
        }
    }

    Box(modifier.fillMaxSize().background(Color.Black)) {
        // ===== 全画面プレビュー（16:9 全体表示・中央配置） =====
        AndroidView(
            factory = { ctx ->
                // 外枠いっぱいに広げ、内部で 16:9 を中央フィット
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
                root.addView(tv)

                try {
                    val client = CameraClient.newBuilder(ctx)
                        .setEnableGLES(true)
                        .setRawImage(false)
                        .setCameraStrategy(CameraUvcStrategy(ctx))
                        .setCameraRequest(
                            CameraRequest.Builder()
                                .setFrontCamera(false)
                                // Switch / キャプチャボード標準 16:9
                                .setPreviewWidth(1280)
                                .setPreviewHeight(720)
                                .create()
                        )
                        .setDefaultRotateType(RotateType.ANGLE_0)
                        .openDebug(false)
                        .build()
                    clientHolder[0] = client

                    tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(
                            surface: SurfaceTexture,
                            width: Int,
                            height: Int
                        ) {
                            try {
                                // 16:9 を明示（Switch画面全体）
                                tv.setAspectRatio(1280, 720)
                                client.openCamera(tv)
                                status = "プレビュー要求済み（USB許可を確認）"
                                startGameAudio(client)
                            } catch (e: Exception) {
                                error = "openCamera: ${e.message}"
                                status = "エラー"
                            }
                        }

                        override fun onSurfaceTextureSizeChanged(
                            surface: SurfaceTexture,
                            width: Int,
                            height: Int
                        ) {
                            tv.setAspectRatio(1280, 720)
                        }

                        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                            try {
                                if (recording) {
                                    try {
                                        client.captureVideoStop()
                                    } catch (_: Exception) {
                                    }
                                    recording = false
                                }
                                try {
                                    client.stopPlayMic()
                                } catch (_: Exception) {
                                }
                                audioOn = false
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
                                status = if (audioOn) "接続済み（音声ON）" else "接続済み"
                            }
                        }
                    }
                } catch (e: Exception) {
                    error = "初期化失敗: ${e.javaClass.simpleName}: ${e.message}"
                    status = "エラー"
                }
                root
            },
            modifier = Modifier.fillMaxSize()
        )

        // ===== 上部ステータス =====
        Column(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.55f))
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "USBキャプチャ",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                    Text(
                        if (connected) {
                            if (audioOn) "● 接続済み / 音声ON" else "● 接続済み"
                        } else status,
                        color = when {
                            error != null -> Color(0xFFFF8A80)
                            connected -> Color(0xFF69F0AE)
                            else -> Color(0xFFB0BEC5)
                        },
                        fontSize = 11.sp
                    )
                    if (error != null) {
                        Text(error!!, color = Color(0xFFFF8A80), fontSize = 10.sp, maxLines = 2)
                    }
                }
                if (recording) {
                    Surface(color = Color.Red, shape = RoundedCornerShape(4.dp)) {
                        Text(
                            "● REC",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
            }
        }

        // ===== 下部操作 =====
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.55f))
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                onClick = {
                    val c = clientHolder[0] ?: return@OutlinedButton
                    try {
                        if (audioOn) {
                            c.stopPlayMic()
                            audioOn = false
                            status = "接続済み（音声OFF）"
                        } else {
                            startGameAudio(c)
                        }
                    } catch (e: Exception) {
                        error = "音声: ${e.message}"
                    }
                },
                enabled = connected || clientHolder[0] != null,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                modifier = Modifier.weight(0.9f)
            ) {
                Text(if (audioOn) "音声OFF" else "音声ON", fontSize = 12.sp)
            }

            OutlinedButton(
                onClick = {
                    val c = clientHolder[0] ?: run {
                        error = "カメラ未初期化"
                        return@OutlinedButton
                    }
                    try {
                        if (recording) {
                            c.captureVideoStop()
                            recording = false
                            status = if (connected) "接続済み（録画停止）" else "録画停止"
                        } else {
                            val dir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
                                ?: context.cacheDir
                            val out = File(dir, "uvc_rec_${System.currentTimeMillis()}.mp4")
                            c.captureVideoStart(object : ICaptureCallBack {
                                override fun onBegin() {
                                    recording = true
                                    status = "録画中…"
                                    error = null
                                }

                                override fun onError(err: String?) {
                                    recording = false
                                    error = err ?: "録画失敗"
                                    status = if (connected) "接続済み" else "エラー"
                                }

                                override fun onComplete(path: String?) {
                                    recording = false
                                    status = if (path != null) {
                                        "接続済み（${File(path).name}）"
                                    } else {
                                        "接続済み（録画完了）"
                                    }
                                }
                            }, out.absolutePath)
                        }
                    } catch (e: Exception) {
                        recording = false
                        error = "録画: ${e.message}"
                    }
                },
                enabled = connected || clientHolder[0] != null,
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = if (recording) Color.Red else Color.White
                ),
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    if (recording) Icons.Default.Stop else Icons.Default.FiberManualRecord,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(if (recording) "停止" else "録画", fontSize = 12.sp)
            }

            Button(
                onClick = {
                    val c = clientHolder[0] ?: run {
                        error = "カメラ未初期化"
                        return@Button
                    }
                    val out = File(
                        context.cacheDir,
                        "uvc_snap_${System.currentTimeMillis()}.jpg"
                    )
                    try {
                        c.captureImage(object : ICaptureCallBack {
                            override fun onBegin() {
                                status = "撮影中…"
                            }

                            override fun onError(err: String?) {
                                error = err ?: "撮影失敗"
                                status = if (connected) "接続済み" else "エラー"
                            }

                            override fun onComplete(path: String?) {
                                if (path.isNullOrBlank()) {
                                    error = "保存パスなし"
                                    return
                                }
                                val f = File(path)
                                if (f.exists()) {
                                    status = "接続済み（撮影完了）"
                                    onSnapshotFile(f)
                                } else {
                                    error = "ファイル未作成"
                                }
                            }
                        }, out.absolutePath)
                    } catch (e: Exception) {
                        error = "capture: ${e.message}"
                    }
                },
                enabled = connected || clientHolder[0] != null,
                modifier = Modifier.weight(1.1f)
            ) {
                Text("このコマを鑑定", fontSize = 12.sp)
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            try {
                val c = clientHolder[0]
                if (recording) {
                    try {
                        c?.captureVideoStop()
                    } catch (_: Exception) {
                    }
                }
                try {
                    c?.stopPlayMic()
                } catch (_: Exception) {
                }
                c?.closeCamera()
            } catch (_: Exception) {
            }
            clientHolder[0] = null
        }
    }
}
