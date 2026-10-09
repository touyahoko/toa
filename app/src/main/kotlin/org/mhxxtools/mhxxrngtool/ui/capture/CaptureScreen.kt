package org.mhxxtools.mhxxrngtool.ui.capture

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.os.Environment
import android.view.TextureView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
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
import com.jiangdg.ausbc.camera.CameraUvcStrategy
import com.jiangdg.ausbc.camera.bean.CameraRequest
import com.jiangdg.ausbc.render.env.RotateType
import com.jiangdg.ausbc.widget.AspectRatioTextureView
import java.io.File

/**
 * AUSBC USBキャプチャ（全画面プレビュー + 録画）
 * Switch本体USB-C → ANYOYO → スマホOTG
 */
@Composable
fun CaptureScreen(
    onSnapshotFile: (File) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var status by remember { mutableStateOf("初期化中…") }
    var error by remember { mutableStateOf<String?>(null) }
    val clientHolder = remember { arrayOfNulls<CameraClient>(1) }
    var connected by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf(false) }
    var recordPath by remember { mutableStateOf<String?>(null) }
    var frameCount by remember { mutableStateOf(0) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val cam = result[Manifest.permission.CAMERA] == true
        if (cam) {
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

    // フレームが来たら接続済み
    LaunchedEffect(frameCount) {
        if (frameCount > 2 && !connected) {
            connected = true
            error = null
            status = "接続済み"
        }
    }

    Box(modifier.fillMaxSize().background(Color.Black)) {
        // ===== 全画面プレビュー =====
        AndroidView(
            factory = { ctx ->
                val tv = AspectRatioTextureView(ctx)
                try {
                    val client = CameraClient.newBuilder(ctx)
                        .setEnableGLES(true)
                        .setRawImage(false)
                        .setCameraStrategy(CameraUvcStrategy(ctx))
                        .setCameraRequest(
                            CameraRequest.Builder()
                                .setFrontCamera(false)
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
                                client.openCamera(tv)
                                status = "プレビュー要求済み（USB許可を確認）"
                            } catch (e: Exception) {
                                error = "openCamera: ${e.message}"
                                status = "エラー"
                            }
                        }

                        override fun onSurfaceTextureSizeChanged(
                            surface: SurfaceTexture,
                            width: Int,
                            height: Int
                        ) = Unit

                        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
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
                            // 実フレーム受信 = 接続成功
                            frameCount++
                            if (client.isCameraOpened() == true && !connected) {
                                connected = true
                                error = null
                                status = "接続済み"
                            }
                        }
                    }
                } catch (e: Exception) {
                    error = "初期化失敗: ${e.javaClass.simpleName}: ${e.message}"
                    status = "エラー"
                }
                tv
            },
            modifier = Modifier.fillMaxSize()
        )

        // ===== 上部ステータス（半透明） =====
        Column(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "USBキャプチャ (AUSBC)",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                    Text(
                        if (connected) "● 接続済み" else status,
                        color = when {
                            error != null -> Color(0xFFFF8A80)
                            connected -> Color(0xFF69F0AE)
                            else -> Color(0xFFB0BEC5)
                        },
                        fontSize = 12.sp
                    )
                    if (error != null) {
                        Text(error!!, color = Color(0xFFFF8A80), fontSize = 10.sp, maxLines = 2)
                    }
                }
                if (recording) {
                    Surface(
                        color = Color.Red,
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            "● REC",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }

        // ===== 下部操作ボタン =====
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 録画
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
                            recordPath = out.absolutePath
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
                                        "接続済み（保存: ${File(path).name}）"
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
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(if (recording) "録画停止" else "録画開始")
            }

            // 静止画 → 鑑定
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
                modifier = Modifier.weight(1f)
            ) {
                Text("このコマを鑑定")
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            try {
                val c = clientHolder[0]
                if (recording) {
                    try { c?.captureVideoStop() } catch (_: Exception) {}
                }
                c?.closeCamera()
            } catch (_: Exception) {
            }
            clientHolder[0] = null
        }
    }
}
