package org.mhxxtools.mhxxrngtool.ui.arduino

import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.mhxxtools.mhxxrngtool.rng.CONTINUE_MASH_FRAME_COST
import org.mhxxtools.mhxxrngtool.rng.KIND_NAMES
import org.mhxxtools.mhxxrngtool.rng.continueMashInfo
import org.mhxxtools.mhxxrngtool.ui.search.SearchScreen
import org.mhxxtools.mhxxrngtool.ui.search.SearchViewModel
import org.mhxxtools.mhxxrngtool.ui.theme.HtmlColors
import java.io.File

/**
 * コンテニュー連打法 Leonardo 用 .ino 生成。
 * 検索タブと同じ検索でフレームを選び、種類名とお守り情報をファイル名にして保存する。
 */
@Composable
fun ArduinoScreen(
    vm: SearchViewModel,
    kind: Int,
    onKind: (Int) -> Unit,
    targetFrame: Long?,
    charmLabel: String,
    onPick: (Long, String) -> Unit
) {
    val context = LocalContext.current
    val searchState by vm.state.collectAsStateWithLifecycle()
    var frameText by remember(targetFrame) { mutableStateOf(targetFrame?.toString() ?: "") }
    var charms by remember { mutableStateOf(1) }
    var sort by remember { mutableStateOf(true) }
    var doSleep by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }

    val frame = frameText.toLongOrNull()
    val kindName = KIND_NAMES.getOrElse(kind) { "風化したお守り" }
    val shortKind = when (kind) {
        0 -> "風化"
        1 -> "古び"
        2 -> "光る"
        else -> "なぞ"
    }
    val (mashes, _, remainder) = continueMashInfo(frame ?: 0L)
    val waitMs = if (frame != null && frame > 0) remainder * 1000L / 30L else 0L
    val code = if (frame == null || frame <= 0) "" else buildIno(
        numContinue = mashes,
        waitMs = waitMs,
        charms = charms.coerceIn(1, 10),
        sort = sort,
        sleep = doSleep,
        frame = frame,
        kindName = kindName,
        charmLabel = charmLabel
    )
    val fileName = inoFileName(frame, shortKind, charmLabel)

    Column(Modifier.fillMaxSize()) {
        Text(
            "Arduino 自動化（Switch 1 / Leonardo / コンテニュー連打法）",
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp,
            color = HtmlColors.Text
        )
        Text("錬金種類", fontSize = 12.sp, color = HtmlColors.Muted)
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf("風化", "古び", "光る", "なぞ").forEachIndexed { idx, label ->
                FilterChip(
                    selected = kind == idx,
                    onClick = {
                        onKind(idx)
                        vm.onKindChanged(idx)
                    },
                    label = { Text(label) }
                )
            }
        }
        if (frame != null && frame > 0) {
            Text(
                "F$frame  $shortKind  ${charmLabel.ifBlank { "お守り未選択" }}",
                fontSize = 13.sp,
                color = HtmlColors.Text
            )
            Text(
                "連打 $mashes 回 / 待ち ${waitMs}ms（残 ${remainder}f・1回${CONTINUE_MASH_FRAME_COST}f）",
                fontSize = 12.sp,
                color = HtmlColors.Muted
            )
            Text(fileName, fontSize = 11.sp, color = HtmlColors.Accent)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("セット", fontSize = 12.sp, color = HtmlColors.Muted)
            listOf(1, 2, 3, 5, 10).forEach { n ->
                FilterChip(selected = charms == n, onClick = { charms = n }, label = { Text("$n") })
            }
        }
        Row {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = sort, onCheckedChange = { sort = it })
                Text("並替", fontSize = 12.sp, color = HtmlColors.Text)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = doSleep, onCheckedChange = { doSleep = it })
                Text("スリープ", fontSize = 12.sp, color = HtmlColors.Text)
            }
        }
        Button(
            onClick = {
                val saved = saveIno(context, fileName, code)
                status = saved ?: "保存に失敗しました"
                Toast.makeText(context, status, Toast.LENGTH_SHORT).show()
            },
            enabled = code.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) { Text("ダウンロード  $fileName") }
        if (status.isNotBlank()) {
            Text(status, fontSize = 12.sp, color = HtmlColors.Accent)
        }
        HorizontalDivider(Modifier.padding(vertical = 6.dp))
        Text("お守り検索（検索タブと同じ）", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = HtmlColors.Text)
        SearchScreen(
            vm = vm,
            onResultTap = { picked ->
                val hit = searchState.results.firstOrNull { it.frame == picked }
                val label = hit?.charm?.skillText()?.let { s ->
                    val slot = hit.charm.slot
                    "$s スロ$slot"
                }.orEmpty()
                frameText = picked.toString()
                onPick(picked, label)
            },
            modifier = Modifier.weight(1f)
        )
    }
}

private fun inoFileName(frame: Long?, kind: String, charm: String): String {
    val f = frame?.toString() ?: "0"
    val body = "$kind ${charm.ifBlank { "お守り" }}"
        .replace(Regex("""[\\/:*?"<>|\n]"""), "")
        .replace(" ", "_")
        .take(60)
    return "F${f}_${body}.ino"
}

private fun saveIno(context: android.content.Context, name: String, text: String): String? {
    return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return null
            context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
            "保存しました: ダウンロード/$name"
        } else {
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
            File(dir, name).writeText(text)
            "保存しました: ${dir.absolutePath}/$name"
        }
    } catch (e: Exception) {
        e.message
    }
}

private fun buildIno(
    numContinue: Long,
    waitMs: Long,
    charms: Int,
    sort: Boolean,
    sleep: Boolean,
    frame: Long,
    kindName: String,
    charmLabel: String
): String = """
// MHXX お守りスナイプ自動化 (Switch 1 / Leonardo)
// 対象: Nintendo Switch 初代・有機EL。Switch 2 では認識しません。
// ライブラリ: NintendoSwitchControlLibrary（Horipad 互換の USB HID）
// boards.txt は Switch 1 用の VID/PID のまま使うこと。
// 目標フレーム: $frame
// 錬金種類: $kindName
// お守り: ${charmLabel.ifBlank { "未選択" }}
// 自動入力: num_continue=$numContinue, wait_ms=$waitMs
// https://pokemonit.com/micon-introduction-2x/
#include <NintendoSwitchControlLibrary.h>

// global変数
unsigned long num_continue = $numContinue; // 総コンティニュー回数
unsigned long wait_ms = $waitMs; // コンティニュー後に待つ時間 (残フレーム@30fps)
unsigned long wait_village_ms = 380; // ロード後，村で待つ時間
bool juju = true; // マカフシギ錬金
bool sleep = ${if (sleep) "true" else "false"}; // sleepするかどうか
bool sort = ${if (sort) "true" else "false"}; // レア度の低い護石保護

int num_charms_set = $charms; // 護石セット回数　(\in [1, 10])

const unsigned long chunk_size = 1000;

void pressAThenBOnce() {
    SwitchControlLibrary().pressButton(Button::A);
    SwitchControlLibrary().sendReport();
    delay(100);
    SwitchControlLibrary().releaseButton(Button::A);
    SwitchControlLibrary().sendReport();
    delay(150);
    SwitchControlLibrary().pressButton(Button::B);
    SwitchControlLibrary().sendReport();
    delay(100);
    SwitchControlLibrary().releaseButton(Button::B);
    SwitchControlLibrary().sendReport();
    delay(150);
}

void pressABAlternately(unsigned long n) {
    for (unsigned long i = 0; i < n; i++) {
        pressAThenBOnce();
    }
}

void setup(){
    // Switch 1 がマイコンをプロコンとして認識するまで B を送る
    pushButton(Button::B, 500, 5);
    delay(50);
    pushButton(Button::A, 250, 4);
    delay(50);
    pushButton(Button::A, 250, 32);
    delay(100);

    unsigned long remaining = num_continue;
    while (remaining > 0) {
        unsigned long n = min(remaining, chunk_size);
        pressABAlternately(n);
        remaining -= n;
    }

    pushButton(Button::A, 250, 2);
    delay(wait_ms);
    pushButton(Button::A, 250, 3);
    delay(9500);
    delay(wait_village_ms);

    SwitchControlLibrary().pressButton(Button::R);
    SwitchControlLibrary().sendReport();
    tiltLeftStick(100, Stick::MIN, 3200);
    SwitchControlLibrary().releaseButton(Button::R);
    SwitchControlLibrary().sendReport();

    pushButton(Button::A, 100);
    pushButton(Button::B, 250, 6);
    pushButton(Button::A, 100);
    if (juju){
        pushHat(Hat::UP);
    }
    else{
        pushHat(Hat::UP, 100, 2);
    }

    for (int i = 0; i < num_charms_set; i++){
        pushButton(Button::A, 10);
        if (i == 0 && sort) {
            pushButton(Button::X, 100, 5);
        }
        pushButton(Button::A, 10);
        pushHat(Hat::DOWN, 10);
        pushButton(Button::A, 10);
        pushHat(Hat::DOWN, 10);
        pushButton(Button::A, 10);
        pushButton(Button::A, 100, 2);
    }
    pushButton(Button::B, 100, 5);

    for (int t = 0; t < num_charms_set; t++){
        SwitchControlLibrary().pressButton(Button::R);
        SwitchControlLibrary().sendReport();
        if (t == 0){
            tiltLeftStick(Stick::MAX, Stick::MIN, 1500);
        }
        else {
            tiltLeftStick(135, Stick::MIN, 4000);
        }
        SwitchControlLibrary().releaseButton(Button::R);
        SwitchControlLibrary().sendReport();

        pushButton(Button::A, 250, 3);
        pushButton(Button::B, 250, 4);
        delay(100);
        pushHat(Hat::UP);
        pushButton(Button::A, 100);
        pushHat(Hat::DOWN);
        pushButton(Button::A, 100);
        pushHat(Hat::DOWN, 50, 3);
        pushButton(Button::A, 50, 5);
        pushButton(Button::B, 250, 4);

        SwitchControlLibrary().pressButton(Button::R);
        SwitchControlLibrary().sendReport();
        tiltLeftStick(Stick::MIN, Stick::NEUTRAL, 800);
        tiltLeftStick(Stick::NEUTRAL, Stick::MIN, 2300);
        SwitchControlLibrary().releaseButton(Button::R);
        SwitchControlLibrary().sendReport();

        pushButton(Button::A, 50, 5);
        delay(8700);
        pushButton(Button::PLUS, 250);
        pushButton(Button::A, 250, 2);
        pushHat(Hat::DOWN, 50, 2);
        pushButton(Button::A, 250);
        pushHat(Hat::RIGHT);
        pushButton(Button::A, 250, 4);
        delay(36000);
        pushHat(Hat::UP);
        pushButton(Button::A, 250);
        pushHat(Hat::LEFT);
        pushButton(Button::A, 250, 5);
        pushButton(Button::B, 250);
        pushButton(Button::A, 250);
        delay(7900);
    }
    pushButton(Button::X, 250);
    pushButton(Button::A, 250, 2);
    delay(2000);
    tiltLeftStick(Stick::MAX, Stick::NEUTRAL, 700);
    pushButton(Button::A, 250, 2);
    pushButton(Button::B, 250, 2);
    delay(100);
    pushButton(Button::A, 250);
    pushHat(Hat::DOWN, 50, 3);
    pushButton(Button::A, 250);
    pushHat(Hat::DOWN, 50);
    pushButton(Button::A, 250, 2);

    if (sleep){
        pushButton(Button::HOME, 100);
        delay(500);
        pushHat(Hat::DOWN, 200);
        pushHat(Hat::LEFT, 200);
        pushButton(Button::A, 500, 4);
    }
}

void loop(){
}
""".trimStart()

