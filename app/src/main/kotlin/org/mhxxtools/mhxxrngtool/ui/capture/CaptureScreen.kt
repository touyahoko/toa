package org.mhxxtools.mhxxrngtool.ui.capture

import android.view.View
import android.widget.FrameLayout
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
import androidx.fragment.app.FragmentActivity
import java.io.File

/**
 * AUSBC UVC キャプチャ画面（AndroidUSBCamera 移植）。
 * Switch本体USB-C → ANYOYO → スマホOTG（ドック不要）
 */
@Composable
fun CaptureScreen(
    onSnapshotFile: (File) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    var status by remember { mutableStateOf("USBカメラ接続待ち…") }
    var error by remember { mutableStateOf<String?>(null) }
    var canSnap by remember { mutableStateOf(false) }
    var fragmentRef by remember { mutableStateOf<CaptureUvcFragment?>(null) }

    Column(
        modifier
            .fillMaxSize()
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "📹 USBキャプチャ (AUSBC / ANYOYO)",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
                Text(
                    "接続（nExt Camera と同じ・ドック不要）:\n" +
                        "Switch本体USB-C → ANYOYO → USB → このスマホ(OTG)\n" +
                        "接続後、自動でプレビューが始まります。",
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
            if (activity == null) {
                Text(
                    "FragmentActivity が必要です",
                    color = Color.White,
                    modifier = Modifier.align(Alignment.Center)
                )
            } else {
                AndroidView(
                    factory = { ctx ->
                        val container = FrameLayout(ctx).apply {
                            id = View.generateViewId()
                            layoutParams = FrameLayout.LayoutParams(
                                FrameLayout.LayoutParams.MATCH_PARENT,
                                FrameLayout.LayoutParams.MATCH_PARENT
                            )
                        }
                        container.post {
                            val frag = CaptureUvcFragment().also { f ->
                                f.onStatus = { msg ->
                                    status = msg
                                    if (msg.contains("成功")) {
                                        canSnap = true
                                        error = null
                                    }
                                }
                                f.onError = { msg ->
                                    error = msg
                                    status = "エラー"
                                    canSnap = false
                                }
                            }
                            fragmentRef = frag
                            activity.supportFragmentManager
                                .beginTransaction()
                                .replace(container.id, frag, "uvc_capture")
                                .commitAllowingStateLoss()
                        }
                        container
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
                    // 再接続: Fragment 差し替え
                    val act = activity ?: return@OutlinedButton
                    status = "再接続中…"
                    error = null
                    canSnap = false
                    // 既存タグを消して再作成はユーザーにタブ再入を促す簡易版
                    fragmentRef?.let {
                        try {
                            it.requireActivity()
                            status = "接続済み。プレビューを確認してください"
                        } catch (_: Exception) {
                            status = "タブを一度離れて戻ると再接続します"
                        }
                    }
                },
                modifier = Modifier.weight(1f)
            ) { Text("状態確認") }

            Button(
                onClick = {
                    fragmentRef?.takeSnapshot { file ->
                        if (file != null) onSnapshotFile(file)
                        else error = "撮影に失敗しました"
                    } ?: run { error = "カメラ未接続" }
                },
                enabled = canSnap || fragmentRef != null,
                modifier = Modifier.weight(1f)
            ) { Text("このコマを鑑定") }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            activity?.supportFragmentManager?.findFragmentByTag("uvc_capture")?.let { f ->
                activity.supportFragmentManager.beginTransaction().remove(f).commitAllowingStateLoss()
            }
        }
    }
}
