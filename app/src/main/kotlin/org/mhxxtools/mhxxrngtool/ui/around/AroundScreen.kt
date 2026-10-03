package org.mhxxtools.mhxxrngtool.ui.around

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.mhxxtools.mhxxrngtool.rng.MHXXEngine
import org.mhxxtools.mhxxrngtool.rng.ORIGIN_NAMES
import org.mhxxtools.mhxxrngtool.ui.search.LabeledSpinner

@Composable
fun AroundScreen(
    vm: AroundViewModel,
    kind: Int,
    modifier: Modifier = Modifier
) {
    val s by vm.state.collectAsStateWithLifecycle()

    Column(modifier.fillMaxSize().padding(8.dp)) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = s.frameText,
                        onValueChange = { vm.setFrameText(it) },
                        label = { Text("フレーム") },
                        placeholder = { Text("0") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = s.countText,
                        onValueChange = { vm.setCountText(it) },
                        label = { Text("前後件数") },
                        singleLine = true,
                        modifier = Modifier.width(90.dp)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LabeledSpinner("出処", ORIGIN_NAMES, ORIGIN_NAMES[s.origin], Modifier.weight(1f)) {
                        vm.setOrigin(ORIGIN_NAMES.indexOf(it).coerceAtLeast(0))
                    }
                    Button(onClick = { vm.show(kind) }) { Text("表示") }
                }
                if (s.label.isNotEmpty()) Text(s.label, fontSize = 12.sp)
            }
        }

        Spacer(Modifier.height(4.dp))

        LazyColumn(Modifier.weight(1f)) {
            items(s.rows) { (frame, charm) ->
                val isCenterFrame = frame == s.frame
                AroundCard(frame, charm, isCenterFrame)
            }
        }
    }
}

@Composable
private fun AroundCard(frame: Long, charm: org.mhxxtools.mhxxrngtool.rng.Charm, isCenter: Boolean) {
    val offset = frame  // offset label not needed here; frame IS the absolute frame
    val rarityColor = Color(charm.rarityColor)
    val bgColor = if (isCenter) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant

    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 0.dp, vertical = 2.dp),
        colors = CardDefaults.cardColors(containerColor = bgColor)
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp, 36.dp).background(rarityColor))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                // HTML周辺表と同じ: フレーム / お守り / ●○○ / Rn
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isCenter) Text("▶ ", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    Text("$frame", fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(charm.skillText(), fontWeight = if (isCenter) FontWeight.Bold else FontWeight.Normal)
                Text("${charm.slotDots()}  ${charm.rarityLabel()}",
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
