package org.mhxxtools.mhxxrngtool.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.mhxxtools.mhxxrngtool.switchtransfer.GalleryStore
import org.mhxxtools.mhxxrngtool.ui.theme.HtmlColors
import androidx.compose.ui.platform.LocalContext

@Composable
fun SettingsScreen(onOpenGallery: () -> Unit = {}, onClearGallery: () -> Unit = {}) {
    val context = LocalContext.current
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(4.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("設定", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = HtmlColors.Text)

        Card(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = HtmlColors.Surface),
            border = BorderStroke(1.dp, HtmlColors.Border)
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Switch 転送", fontWeight = FontWeight.SemiBold, color = HtmlColors.Text)
                Text(
                    "アルバム → 共有 → スマートフォンへ送る のQRを、ギャラリータブで読み取ります。\n" +
                        "1つ目のQRでSwitchのWi-Fiに接続、2つ目で画像URLを開き、アプリ内ギャラリーへ自動保存します。",
                    fontSize = 12.sp,
                    color = HtmlColors.Muted
                )
                OutlinedButton(onClick = onOpenGallery, modifier = Modifier.fillMaxWidth()) {
                    Text("ギャラリーを開く")
                }
            }
        }

        Card(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = HtmlColors.Surface),
            border = BorderStroke(1.dp, HtmlColors.Border)
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("データ", fontWeight = FontWeight.SemiBold, color = HtmlColors.Text)
                val n = GalleryStore.list(context).size
                Text("アプリ内ギャラリー: ${n} 件", fontSize = 13.sp, color = HtmlColors.Text)
                OutlinedButton(
                    onClick = {
                        GalleryStore.clearAll(context)
                        onClearGallery()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("ギャラリーをすべて削除")
                }
            }
        }

        Card(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = HtmlColors.Surface),
            border = BorderStroke(1.dp, HtmlColors.Border)
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("アプリ情報", fontWeight = FontWeight.SemiBold, color = HtmlColors.Text)
                Text("MHXX RNG Tool", fontSize = 13.sp, color = HtmlColors.Text)
                Text("鑑定: 錬金リーダー方式テンプレ照合（公式134）", fontSize = 12.sp, color = HtmlColors.Muted)
                Text("写真は端末内のみで処理します", fontSize = 12.sp, color = HtmlColors.Muted)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
