package org.mhxxtools.mhxxrngtool.ui.hid

import android.Manifest
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.mhxxtools.mhxxrngtool.hid.BluetoothHidGamepad
import org.mhxxtools.mhxxrngtool.ui.theme.HtmlColors

/**
 * 設定内: Bluetooth HID ゲームパッド（実験）
 * オンのときだけ registerApp / オフで unregister。
 */
@Composable
fun HidControllerSection() {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("オフ") }
    var connected by remember { mutableStateOf<String?>(null) }
    val pad = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            BluetoothHidGamepad(context.applicationContext).also { g ->
                g.listener = object : BluetoothHidGamepad.Listener {
                    override fun onState(msg: String) { status = msg }
                    override fun onConnected(deviceName: String?) { connected = deviceName }
                    override fun onDisconnected() { connected = null }
                }
            }
        } else null
    }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it }) {
            enabled = true
            pad?.start()
        } else {
            Toast.makeText(context, "Bluetooth権限が必要です", Toast.LENGTH_SHORT).show()
            enabled = false
        }
    }

    DisposableEffect(Unit) {
        onDispose { pad?.stop() }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .background(HtmlColors.Surface, RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Text("Bluetooth HID コントローラ（実験）", color = HtmlColors.Text, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Spacer(Modifier.height(6.dp))
        Text(
            "オンのときだけ端末を汎用ゲームパッドHIDとして登録します。\n" +
            "・Class of Device の任意変更はアプリ権限では不可です（HID Gamepad登録のみ）\n" +
            "・Switch が認識する保証はありません（非対応のことが多いです）",
            color = HtmlColors.Muted, fontSize = 11.sp
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("HIDを有効化", color = HtmlColors.Text, modifier = Modifier.weight(1f))
            Switch(
                checked = enabled,
                onCheckedChange = { on ->
                    if (on) {
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                            Toast.makeText(context, "Android 9+ が必要", Toast.LENGTH_SHORT).show()
                            return@Switch
                        }
                        val perms = buildList {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                add(Manifest.permission.BLUETOOTH_CONNECT)
                                add(Manifest.permission.BLUETOOTH_ADVERTISE)
                                add(Manifest.permission.BLUETOOTH_SCAN)
                            } else {
                                add(Manifest.permission.BLUETOOTH)
                                add(Manifest.permission.BLUETOOTH_ADMIN)
                            }
                        }.toTypedArray()
                        permLauncher.launch(perms)
                    } else {
                        enabled = false
                        pad?.stop()
                        status = "オフ"
                        connected = null
                    }
                }
            )
        }
        Text("状態: $status", color = HtmlColors.Muted, fontSize = 12.sp)
        Text(
            "接続先: ${connected ?: "なし"}",
            color = if (connected != null) Color(0xFF34D399) else HtmlColors.Muted,
            fontSize = 12.sp
        )

        if (enabled && pad != null) {
            Spacer(Modifier.height(12.dp))
            Text("仮想パッド（押している間送信）", color = HtmlColors.Text, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            // 簡易 ABXY + 方向
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    PadBtn("↑") { pad.setButton(12, it) }
                    Row {
                        PadBtn("←") { pad.setButton(14, it) }
                        Spacer(Modifier.width(28.dp))
                        PadBtn("→") { pad.setButton(15, it) }
                    }
                    PadBtn("↓") { pad.setButton(13, it) }
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    PadBtn("X") { pad.setButton(2, it) }
                    Row {
                        PadBtn("Y") { pad.setButton(3, it) }
                        Spacer(Modifier.width(8.dp))
                        PadBtn("A") { pad.setButton(0, it) }
                    }
                    PadBtn("B") { pad.setButton(1, it) }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                PadBtn("L") { pad.setButton(4, it) }
                PadBtn("R") { pad.setButton(5, it) }
                PadBtn("ZL") { pad.setButton(6, it) }
                PadBtn("ZR") { pad.setButton(7, it) }
            }
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                PadBtn("+") { pad.setButton(9, it) }
                PadBtn("-") { pad.setButton(8, it) }
                PadBtn("Home") { pad.setButton(10, it) }
            }
        }
    }
}

@Composable
private fun PadBtn(label: String, onPress: (Boolean) -> Unit) {
    Box(
        Modifier
            .padding(4.dp)
            .size(48.dp)
            .clip(CircleShape)
            .background(Color(0xFF243044))
            .pointerInput(label) {
                detectTapGestures(
                    onPress = {
                        onPress(true)
                        try {
                            awaitRelease()
                        } finally {
                            onPress(false)
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}
