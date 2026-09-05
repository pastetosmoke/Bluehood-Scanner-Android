package com.faker.bluehood.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.faker.bluehood.ble.*
import kotlinx.coroutines.delay

/**
 * ホット/コールド探索。自分に付けられたタグを物理的に見つけるための画面。
 *
 * 「⚠追尾されている」と分かっても鞄のどこにあるかは分からない、という穴を埋める。
 * 電波強度の相対変化しか使わないので相手座標は扱わない。
 */
@Composable
fun HuntScreen(vm: BluehoodViewModel) {
    val hunt by HuntState.state.collectAsState()

    // trend も stale も「経過時間」で決まるため、受信が無くても定期的に再評価する必要がある。
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) { now = System.currentTimeMillis(); delay(400) }
    }

    if (hunt.target == null) HuntPicker(vm) else HuntActive(hunt, now)
}

@Composable
private fun HuntPicker(vm: BluehoodViewModel) {
    val trackers by vm.trackers.collectAsState(emptyList())
    val st by ScanState.state.collectAsState()

    val byType = trackers.filter { it.trackerType != null }
        .groupBy { it.trackerType!! }
        .map { (type, list) -> type to list.maxByOrNull { it.lastSeen }!! }
        .sortedByDescending { it.second.stalkerScore }

    LazyColumn(Modifier.fillMaxSize().padding(12.dp)) {
        if (st.status != ScanState.Status.SCANNING) item {
            Card(
                Modifier.fillMaxWidth().padding(bottom = 12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
            ) {
                Text(
                    "スキャンが動作していないため探索できません。上部のSTARTを押してください。",
                    Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        item {
            Text("探すものを選ぶ", style = MaterialTheme.typography.titleMedium)
            Text(
                "選んだ種別の電波強度を追い、近づくと強く、離れると弱くなります。" +
                    "鞄や車内を動き回って一番強くなる場所を探してください。",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(12.dp))
        }

        if (byType.isEmpty()) item {
            Text("検出済みのトラッカーがありません。")
        }

        items(byType) { (type, c) ->
            ListItem(
                headlineContent = { Text(type) },
                supportingContent = {
                    Text(
                        if (c.stalkerScore >= 5.0) "⚠ 追尾の疑い (score ${"%.1f".format(c.stalkerScore)})"
                        else "追尾兆候なし"
                    )
                },
                trailingContent = {
                    Button(
                        enabled = st.status == ScanState.Status.SCANNING,
                        onClick = { HuntState.start(HuntState.Target(trackerType = type, label = type)) }
                    ) { Text("探す") }
                }
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun HuntActive(hunt: HuntState.State, now: Long) {
    val stale = hunt.isStale(now)
    val rssi = hunt.smoothedRssi(now)
    val heat = hunt.heat(now)
    val trend = hunt.trend(now)

    Column(
        Modifier.fillMaxSize().padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(hunt.target?.label ?: "", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(24.dp))

        // 受信途絶を「遠い」と同じ見た目にしない。弱いのと届いていないのは別の事実。
        if (stale) {
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("見失いました", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "${(now - hunt.lastSeen) / 1000}秒間受信がありません。" +
                            "これは「遠い」ではなく「届いていない」状態です。",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        } else {
            Text(
                when (trend) {
                    HuntState.Trend.CLOSER -> "近づいています"
                    HuntState.Trend.FARTHER -> "離れています"
                    HuntState.Trend.FLAT -> "変化なし"
                    HuntState.Trend.UNKNOWN -> "測定中…"
                },
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(20.dp))
            LinearProgressIndicator(
                progress = { heat ?: 0f },
                modifier = Modifier.fillMaxWidth().height(16.dp)
            )
            Spacer(Modifier.height(8.dp))
            Text(
                rssi?.let { "$it dBm" } ?: "受信待ち",
                style = MaterialTheme.typography.titleLarge
            )
            // 距離に換算しないのは、RSSIからの距離推定が環境依存で嘘になるため。
            Text("強さの相対表示です。距離ではありません。", style = MaterialTheme.typography.bodySmall)
        }

        Spacer(Modifier.height(24.dp))
        hunt.bestRssi?.let {
            Text("これまでの最接近: $it dBm", style = MaterialTheme.typography.bodyMedium)
            Text("この値に近づくほど、さっき一番強かった場所に戻っています。",
                style = MaterialTheme.typography.bodySmall)
        }

        Spacer(Modifier.weight(1f))
        Button(onClick = { HuntState.stop() }, modifier = Modifier.fillMaxWidth()) {
            Text("探索を終了")
        }
    }
}
