package org.mhxxtools.mhxxrngtool.ui.arduino

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.mhxxtools.mhxxrngtool.rng.CONTINUE_MASH_FRAME_COST
import org.mhxxtools.mhxxrngtool.rng.continueMashInfo
import org.mhxxtools.mhxxrngtool.ui.theme.HtmlColors

/**
 * コンテニュー連打法 Leonardo 用 .ino 生成。
 * 目標フレームから num_continue / wait_ms を自動入力する。
 */
@Composable
fun ArduinoScreen(
    targetFrame: Long?,
    onFrameChange: (Long?) -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var frameText by remember(targetFrame) {
        mutableStateOf(targetFrame?.toString() ?: "")
    }
    var juju by remember { mutableStateOf(true) }
    var charms by remember { mutableStateOf(1) }
    var sort by remember { mutableStateOf(true) }
    var doSleep by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }

    val frame = frameText.toLongOrNull()
    val (mashes, _, remainder) = continueMashInfo(frame ?: 0L)
    // 残りフレームを 30fps でミリ秒化（コンテニュー後の待ち）
    val waitMs = if (frame != null && frame > 0) remainder * 1000L / 30L else 0L
    val code = remember(frame, juju, charms, sort, doSleep, mashes, waitMs) {
        if (frame == null || frame <= 0) TEMPLATE_PLACEHOLDER
        else buildIno(
            numContinue = mashes,
            waitMs = waitMs,
            juju = juju,
            charms = charms.coerceIn(1, 10),
            sort = sort,
            sleep = doSleep,
            frame = frame
        )
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            "Arduino 自動化（Leonardo / コンテニュー連打法）",
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp,
            color = HtmlColors.Text
        )
        Text(
            "検索結果をタップするか目標フレームを入力すると、連打回数と待ち時間がコードに入ります。",
            fontSize = 12.sp,
            color = HtmlColors.Muted
        )

        OutlinedTextField(
            value = frameText,
            onValueChange = {
                frameText = it.filter { c -> c.isDigit() }
                onFrameChange(frameText.toLongOrNull())
            },
            label = { Text("目標フレーム") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = fieldColors()
        )

        if (frame != null && frame > 0) {
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = HtmlColors.Surface2)
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("連打回数 num_continue = $mashes", fontSize = 13.sp, color = HtmlColors.Text)
                    Text("待ち時間 wait_ms = ${waitMs}（残 ${remainder}f @30fps）", fontSize = 13.sp, color = HtmlColors.Text)
                    Text("1回あたり ${CONTINUE_MASH_FRAME_COST}f 消費", fontSize = 11.sp, color = HtmlColors.Muted)
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected = juju, onClick = { juju = true }, label = { Text("マカフシギ") })
            FilterChip(selected = !juju, onClick = { juju = false }, label = { Text("天運") })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("護石セット回数", fontSize = 12.sp, color = HtmlColors.Muted)
            listOf(1, 2, 3, 5, 10).forEach { n ->
                FilterChip(selected = charms == n, onClick = { charms = n }, label = { Text("$n") })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = sort, onCheckedChange = { sort = it })
                Text("レア度低保護(並替)", fontSize = 12.sp, color = HtmlColors.Text)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = doSleep, onCheckedChange = { doSleep = it })
                Text("終了後スリープ", fontSize = 12.sp, color = HtmlColors.Text)
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    clipboard.setText(AnnotatedString(code))
                    status = "クリップボードにコピーしました"
                },
                enabled = frame != null && frame > 0
            ) { Text("コピー") }
            Button(
                onClick = {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "mhxx_snipe_F${frame}.ino")
                        putExtra(Intent.EXTRA_TEXT, code)
                    }
                    context.startActivity(Intent.createChooser(send, "Arduinoコードを共有"))
                    status = "エクスポート画面を開きました"
                },
                enabled = frame != null && frame > 0
            ) { Text("エクスポート") }
        }
        if (status.isNotBlank()) {
            Text(status, fontSize = 12.sp, color = HtmlColors.Accent)
        }

        Text("生成コード", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = HtmlColors.Text)
        Box(
            Modifier
                .fillMaxWidth()
                .border(1.dp, HtmlColors.Border, RoundedCornerShape(8.dp))
                .background(HtmlColors.Surface, RoundedCornerShape(8.dp))
                .padding(10.dp)
        ) {
            Text(
                code,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = HtmlColors.Text,
                lineHeight = 14.sp
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = HtmlColors.Text,
    unfocusedTextColor = HtmlColors.Text,
    focusedBorderColor = HtmlColors.Accent,
    unfocusedBorderColor = HtmlColors.Border,
    focusedLabelColor = HtmlColors.Muted,
    unfocusedLabelColor = HtmlColors.Muted,
    cursorColor = HtmlColors.Accent
)

private const val TEMPLATE_PLACEHOLDER =
    "// 目標フレームを入力するか、検索結果をタップしてください。\n"

private fun buildIno(
    numContinue: Long,
    waitMs: Long,
    juju: Boolean,
    charms: Int,
    sort: Boolean,
    sleep: Boolean,
    frame: Long
): String = """
// MHXX お守りスナイプ自動化 (Leonardo / NintendoSwitchControlLibrary)
// 目標フレーム: $frame
// 自動入力: num_continue=$numContinue, wait_ms=$waitMs
// https://pokemonit.com/micon-introduction-2x/
#include <NintendoSwitchControlLibrary.h>

// global変数
unsigned long num_continue = $numContinue; // 総コンティニュー回数
unsigned long wait_ms = $waitMs; // コンティニュー後に待つ時間 (残フレーム@30fps)
unsigned long wait_village_ms = 380; // ロード後，村で待つ時間
bool juju = ${if (juju) "true" else "false"}; // true: juju (マカフシギ), false: halcyon (天運)
bool sleep = ${if (sleep) "true" else "false"}; // sleepするかどうか
bool sort = ${if (sort) "true" else "false"}; // レア度の低い護石保護

int num_charms_set = $charms; // 護石セット回数　(\in [1, 10])

// 1回の関数呼び出しで処理する最大回数
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

// マイコンのセット時に1度だけ行われる処理
void setup(){
    delay(50);  // 書き込み猶予

    // MHXXの選択から250 msごとにA連打し，「ゲームモードの選択」で止める
    // 止まらない場合は連打数を36回から増減すること
    pushButton(Button::A, 250, 4);
    delay(50);
    pushButton(Button::A, 250, 32);

    delay(100); // continueで止める

    // continueを連打
    unsigned long remaining = num_continue;

    while (remaining > 0) {
        unsigned long n = min(remaining, chunk_size);
        pressABAlternately(n);
        remaining -= n;
    }

    pushButton(Button::A, 250, 2); // final continue + キャラクター選択

    delay(wait_ms); // キャラクター選択画面での事後の待ち時間

    pushButton(Button::A, 250, 3); // ゲーム開始までA連打

    delay(9500); // ゲームのロード時の待機時間
    delay(wait_village_ms); // 村で調整する待機時間

    SwitchControlLibrary().pressButton(Button::R); //Rを押し始める
    SwitchControlLibrary().sendReport();
    tiltLeftStick(100, Stick::MIN, 3200); //左上にダッシュ
    SwitchControlLibrary().releaseButton(Button::R); // Rを離す
    SwitchControlLibrary().sendReport();

    pushButton(Button::A, 100);    // マカ錬金屋さんに話しかけ
    pushButton(Button::B, 250, 6); // 会話スキップ
    pushButton(Button::A, 100);    // 護石錬金
    if (juju){
        pushHat(Hat::UP); // マカフシギ錬金術を選択
    }
    else{
        pushHat(Hat::UP, 100, 2); // 天運の錬金術を選択
    }

    for (int i = 0; i < num_charms_set; i++){
        pushButton(Button::A, 10);
        if (i == 0 && sort) {
            pushButton(Button::X, 100, 5); // 護石の並び替え
        }
        pushButton(Button::A, 10); // 1番目の護石選択
        pushHat(Hat::DOWN, 10);
        pushButton(Button::A, 10); // 2番目の護石選択
        pushHat(Hat::DOWN, 10);
        pushButton(Button::A, 10); // 3番目の護石選択
        pushButton(Button::A, 100, 2); // 護石投入
    }
    pushButton(Button::B, 100, 5); // 会話を終了する

    // ケルマラに移行
    for (int t = 0; t < num_charms_set; t++){
        SwitchControlLibrary().pressButton(Button::R); //Rを押し始める
        SwitchControlLibrary().sendReport();
        if (t == 0){
            tiltLeftStick(Stick::MAX, Stick::MIN, 1500); // 右上にダッシュ
        }
        else {
            tiltLeftStick(135, Stick::MIN, 4000); //上にダッシュ
        }
        SwitchControlLibrary().releaseButton(Button::R);
        SwitchControlLibrary().sendReport();

        pushButton(Button::A, 250, 3); // 受付嬢に話しかけ
        pushButton(Button::B, 250, 4); // 会話スキップ
        delay(100);
        pushHat(Hat::UP); // 下位クエストを選択
        pushButton(Button::A, 100);
        pushHat(Hat::DOWN); // Lv1を選択
        pushButton(Button::A, 100);
        pushHat(Hat::DOWN, 50, 3); // 森の中のケルビを選択
        pushButton(Button::A, 50, 5); // クエスト受注
        pushButton(Button::B, 250, 4); // 会話スキップ

        //クエストに出発
        SwitchControlLibrary().pressButton(Button::R); //Rを押し始める
        SwitchControlLibrary().sendReport();
        tiltLeftStick(Stick::MIN, Stick::NEUTRAL, 800);  // 左に移動
        tiltLeftStick(Stick::NEUTRAL, Stick::MIN, 2300); // 右に移動
        SwitchControlLibrary().releaseButton(Button::R);
        SwitchControlLibrary().sendReport();

        pushButton(Button::A, 50, 5); // クエストに出発

        delay(8700); // クエスト開始までの待機時間

        pushButton(Button::PLUS, 250); //メニューを開く
        pushButton(Button::A, 250, 2); //ケルビの角を選択
        pushHat(Hat::DOWN, 50, 2); // 納品を選択
        pushButton(Button::A, 250);
        pushHat(Hat::RIGHT); // 上限の3つを選択
        pushButton(Button::A, 250, 4); // 納品

        delay(36000); // クエスト終了までの待機時間

        //報酬売却
        pushHat(Hat::UP);
        pushButton(Button::A, 250);
        pushHat(Hat::LEFT);
        pushButton(Button::A, 250, 5);
        pushButton(Button::B, 250); //セーブしない
        pushButton(Button::A, 250);

        delay(7900); // クエストから帰還するまでの待機時間
    }
    // ココット村自宅へ移動
    pushButton(Button::X, 250);
    pushButton(Button::A, 250, 2);

    //マカ鑑定
    delay(2000); // 自宅までのロード時間
    tiltLeftStick(Stick::MAX, Stick::NEUTRAL, 700);
    pushButton(Button::A, 250, 2); // ルームサービスに話しかけ
    pushButton(Button::B, 250, 2);
    delay(100);
    pushButton(Button::A, 250);
    pushHat(Hat::DOWN, 50, 3);
    pushButton(Button::A, 250); // マカ錬金
    pushHat(Hat::DOWN, 50);
    pushButton(Button::A, 250, 2); // 鑑定

    // Sleepする
    if (sleep){
        pushButton(Button::HOME, 100);
        delay(500);
        pushHat(Hat::DOWN, 200);
        pushHat(Hat::LEFT, 200);
        pushButton(Button::A, 500, 4);
    }
}

// ここに記述した内容がループされ続ける
void loop(){
}
""".trimStart()
