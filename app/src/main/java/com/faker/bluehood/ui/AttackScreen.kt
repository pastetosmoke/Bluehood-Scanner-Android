package com.faker.bluehood.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.faker.bluehood.data.AttackEvent
import com.faker.bluehood.detect.AttackTestInjector
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

private fun fmtJst(ms: Long): String {
    val sdf = SimpleDateFormat("MM/dd HH:mm:ss", Locale.JAPAN)
    sdf.timeZone = TimeZone.getTimeZone("Asia/Tokyo")
    return sdf.format(Date(ms))
}

/**
 * BLE攻撃シグネチャ検出画面。
 *
 * 上部: Cerberus Blue各攻撃モジュールのテストボタン(合成データをDBに注入)
 * 下部: 実検出＋テストの全イベント一覧
 *
 * 検出できる攻撃: ペイロード層のFuzzing・DoSフラッディング・デバイス偽装(BIAS)
 * 検出できない攻撃: KNOB/BlueBorne/BlueFrag/BleedingTooth — 接続確立後のHCIレイヤで不可視
 */
@Composable
fun AttackScreen(vm: BluehoodViewModel) {
    val events by vm.attackEvents.collectAsState(emptyList())
    var showTests by remember { mutableStateOf(false) }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        // ---- 検出範囲の説明 ----
        item {
            Spacer(Modifier.height(8.dp))
            Text("BLE攻撃シグネチャ検出", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "広告パケット層で検出: Fuzzing(不正AD構造)・DoSフラッディング・デバイス偽装。" +
                    "接続層の攻撃(KNOB/BlueBorne/BlueFrag)はBLE広告スキャンでは不可視。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
        }

        // ---- Cerberus Blueテストモード ----
        item {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "Cerberus Blueテスト注入",
                    style = MaterialTheme.typography.titleSmall,
                )
                TextButton(onClick = { showTests = !showTests }) {
                    Text(if (showTests) "折りたたむ" else "展開")
                }
            }
        }

        if (showTests) {
            item {
                Card(
                    Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Text(
                        "テストボタンは合成データ(isTest=true)をDBに注入し、検出UIが正しく動作するか確認します。" +
                            "実際にBLE攻撃パケットを送信するわけではありません。",
                        Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            items(AttackTestInjector.CASES) { tc ->
                OutlinedButton(
                    onClick = { vm.injectTestAttack(tc) },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                ) {
                    Column(Modifier.fillMaxWidth()) {
                        Text(tc.label, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            tc.cerberusModule,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            item { Spacer(Modifier.height(12.dp)) }
        }

        // ---- 検出イベント一覧 ----
        item {
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text(
                "検出イベント (${events.size})",
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.height(4.dp))
        }

        if (events.isEmpty()) {
            item {
                Text(
                    "攻撃シグネチャは検出されていません。テストボタンで動作を確認できます。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        items(events, key = { it.id }) { ev ->
            AttackEventCard(ev)
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun AttackEventCard(ev: AttackEvent) {
    val borderColor = if (ev.isTest) MaterialTheme.colorScheme.outline
    else MaterialTheme.colorScheme.error

    Card(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (ev.isTest) MaterialTheme.colorScheme.surfaceVariant
            else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f)
        ),
        border = CardDefaults.outlinedCardBorder().let {
            androidx.compose.foundation.BorderStroke(1.dp, borderColor)
        }
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text(
                    if (ev.isTest) "[テスト] ${ev.title}" else "⚠ ${ev.title}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    fmtJst(ev.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ev.cve?.let {
                Spacer(Modifier.height(2.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "MAC: ${ev.mac ?: "-"}  RSSI: ${ev.rssi} dBm",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                ev.evidence,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}
