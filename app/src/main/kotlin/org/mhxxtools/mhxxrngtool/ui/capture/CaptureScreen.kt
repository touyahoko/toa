package org.mhxxtools.mhxxrngtool.ui.capture

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * USBキャプチャ (ANYOYO 等) — nExt Camera と同じ外部カメラ経路。
 */
@Composable
fun CaptureScreen(
    vm: CaptureViewModel,
    onSnapshotForOcr: (ByteArray) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val s = vm.state.collectAsStateWithLifecycle().value
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var permitted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { ok -> permitted = ok }

    var previewView by remember { mutableStateOf<PreviewView?>(null) }

    LaunchedEffect(permitted, previewView) {
        if (permitted && previewView != null) {
            vm.bind(context, lifecycleOwner, previewView!!)
        }
    }
    DisposableEffect(Unit) {
        onDispose { vm.unbind() }
    }

    Column(
        modifier
            .fillMaxSize()
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("📹 Switchキャプチャ (USB / ANYOYO)", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(
                    "接続（写真・nExt Camera と同じ・ドック不要）:\n" +
                        "Switch本体USB-C → ANYOYO → USB → このスマホ(OTG)\n" +
                        "映らないときは「再接続」→ カメラ一覧で「外部(UVC)」を選択",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    s.status,
                    fontSize = 12.sp,
                    color = if (s.error != null) MaterialTheme.colorScheme.error
                    else Color(0xFF4CAF50)
                )
                if (s.error != null) {
                    Text(s.error, fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("FPS: ${"%.1f".format(s.fps)}", fontSize = 12.sp)
                    Text(s.resolution, fontSize = 12.sp)
                    Text(s.cameraLabel, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        // カメラ選択（nExt Camera のように複数カメラを切り替え）
        if (s.cameras.isNotEmpty()) {
            Text("カメラ選択", fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                s.cameras.forEach { cam ->
                    val selected = cam.id == s.selectedCameraId
                    FilterChip(
                        selected = selected,
                        onClick = { vm.selectCamera(cam.id) },
                        label = {
                            Text(
                                cam.label,
                                fontSize = 11.sp,
                                maxLines = 1
                            )
                        }
                    )
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
            if (!permitted) {
                Column(
                    Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("カメラ権限が必要です", color = Color.White)
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                        Text("許可する")
                    }
                }
            } else {
                AndroidView(
                    factory = { ctx ->
                        PreviewView(ctx).also {
                            it.scaleType = PreviewView.ScaleType.FIT_CENTER
                            it.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                            previewView = it
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = {
                    if (permitted) previewView?.let { vm.bind(context, lifecycleOwner, it) }
                    else permissionLauncher.launch(Manifest.permission.CAMERA)
                },
                modifier = Modifier.weight(1f)
            ) { Text("再接続") }

            Button(
                onClick = {
                    val jpeg = vm.snapshotJpeg()
                    if (jpeg != null) onSnapshotForOcr(jpeg)
                },
                enabled = s.hasFrame,
                modifier = Modifier.weight(1f)
            ) { Text("このコマを鑑定") }
        }
    }
}
