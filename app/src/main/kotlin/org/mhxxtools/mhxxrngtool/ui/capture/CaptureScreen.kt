package org.mhxxtools.mhxxrngtool.ui.capture

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.view.TextureView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
 * AUSBC CameraClient 直結（Fragment 不使用 → クラッシュ回避）
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
    var ready by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            status = "カメラ権限OK。USB接続を確認してください"
        } else {
            error = "CAMERA権限が必要です（設定から許可）"
            status = "権限エラー"
        }
    }

    LaunchedEffect(Unit) {
        val ok = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (!ok) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        } else {
            status = "USBカメラ接続待ち…"
        }
    }

    Column(
        modifier
            .fillMaxSize()
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "📹 USBキャプチャ (AUSBC)",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
                Text(
                    "接続: Switch本体USB-C → ANYOYO → このスマホ(OTG)\n" +
                        "※ドック不要 / nExt Camera と同じ経路",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    status,
                    fontSize = 12.sp,
                    color = if (error != null) MaterialTheme.colorScheme.error else Color(0xFF4CAF50)
                )
                if (error != null) {
                    Text(error!!, fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                }
            }
        }

        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black)
        ) {
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
                                    status = "プレビュー要求済み（USB許可ダイアログを確認）"
                                    ready = true
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
                                    client.closeCamera()
                                } catch (_: Exception) {
                                }
                                ready = false
                                return true
                            }

                            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
                        }
                        status = "TextureView準備完了"
                    } catch (e: Exception) {
                        error = "初期化失敗: ${e.javaClass.simpleName}: ${e.message}"
                        status = "エラー"
                    }
                    tv
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = {
                    try {
                        val c = clientHolder[0]
                        if (c == null) {
                            status = "Client未作成"
                            return@OutlinedButton
                        }
                        // 再open
                        status = "再接続を試行…"
                        error = null
                    } catch (e: Exception) {
                        error = e.message
                    }
                },
                modifier = Modifier.weight(1f)
            ) { Text("状態確認") }

            Button(
                onClick = {
                    val c = clientHolder[0]
                    if (c == null) {
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
                                status = "エラー"
                            }

                            override fun onComplete(path: String?) {
                                if (path.isNullOrBlank()) {
                                    error = "保存パスなし"
                                    return
                                }
                                val f = File(path)
                                if (f.exists()) {
                                    status = "撮影完了"
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
                enabled = ready || clientHolder[0] != null,
                modifier = Modifier.weight(1f)
            ) { Text("このコマを鑑定") }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            try {
                clientHolder[0]?.closeCamera()
            } catch (_: Exception) {
            }
            clientHolder[0] = null
        }
    }
}
