package com.faker.bluehood.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.faker.bluehood.ble.ScanService
import com.faker.bluehood.ble.ScanState
import com.faker.bluehood.detect.PlaceFingerprint
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.cachemanager.CacheManager
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** 寄付先(TRC20 / Tron)。ネットワークを間違えると資産が失われるため必ず併記する。 */
private const val TRC20_ADDRESS = "TW6xjgEJwJpgXQbKaEqTYg9fxa3KeWGrZD"

private fun formatJst(epochMs: Long): String {
    val sdf = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.JAPAN)
    sdf.timeZone = TimeZone.getTimeZone("Asia/Tokyo")
    return sdf.format(Date(epochMs))
}

/** 5画面: (1)近隣一覧 (2)尾行アラート (3)証拠 (4)地図 (5)情報。 */
class MainActivity : ComponentActivity() {

    private val perms = ActivityResultContracts.RequestMultiplePermissions()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // osmdroid: タイルキャッシュのパスを初期化(未初期化だと mWriter=null でタイルが描画されない)。
        // その後 UA をパッケージ名に(OSMタイルサーバは既定UAを弾くため)。
        Configuration.getInstance().load(this, getSharedPreferences("osmdroid", MODE_PRIVATE))
        Configuration.getInstance().userAgentValue = packageName
        val launcher = registerForActivityResult(perms) { startScanIfGranted() }

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                var tab by rememberSaveable { mutableStateOf(0) }   // Activity再生成でも維持
                val vm: BluehoodViewModel = viewModel()
                Scaffold(
                    topBar = { ScanControlBar() },
                    bottomBar = {
                        NavigationBar {
                            listOf("近隣" to 0, "尾行" to 1, "探索" to 2, "証拠" to 3, "地図" to 4, "攻撃" to 5, "情報" to 6)
                                .forEach { (t, i) ->
                                    NavigationBarItem(
                                        selected = tab == i, onClick = { tab = i },
                                        icon = {}, label = { Text(t) }
                                    )
                                }
                        }
                    }
                ) { pad ->
                    Box(Modifier.padding(pad)) {
                        when (tab) {
                            0 -> NeighborhoodScreen(vm)
                            1 -> StalkerScreen(vm)
                            2 -> HuntScreen(vm)
                            3 -> EvidenceScreen(vm)
                            4 -> MapScreen(vm)
                            5 -> AttackScreen(vm)
                            else -> SupportScreen()
                        }
                    }
                }
            }
        }
        launcher.launch(requiredPermissions())
    }

    private fun requiredPermissions(): Array<String> = buildList {
        add(Manifest.permission.BLUETOOTH_SCAN)
        add(Manifest.permission.BLUETOOTH_CONNECT)
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

    private fun startScanIfGranted() {
        val ok = ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) ==
            PackageManager.PERMISSION_GRANTED
        if (ok) ContextCompat.startForegroundService(this, Intent(this, ScanService::class.java))
    }
}

/**
 * 全タブ共通の状態バー。「スキャンが死んでいる」と「脅威が無い」を
 * 画面上で絶対に混同させないための最重要UI。
 */
@Composable
fun ScanControlBar() {
    val ctx = LocalContext.current
    val st by ScanState.state.collectAsState()
    val running = st.status == ScanState.Status.SCANNING || st.status == ScanState.Status.STALLED

    val (dot, label) = when (st.status) {
        ScanState.Status.SCANNING -> Color(0xFF4CAF50) to "スキャン中"
        ScanState.Status.STALLED -> Color(0xFFFFC107) to "⚠ 受信途絶"
        ScanState.Status.BLOCKED -> Color(0xFFF44336) to "⚠ スキャン不可"
        ScanState.Status.STOPPED -> Color(0xFF9E9E9E) to "停止中"
    }

    Surface(tonalElevation = 3.dp) {
        Row(
            Modifier.fillMaxWidth()
                .statusBarsPadding()          // edge-to-edge なので時刻/電池表示と重ならせない
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(dot))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.titleSmall)
                val detail = st.reason ?: if (st.status == ScanState.Status.SCANNING)
                    "受信 ${st.results} 件" else null
                detail?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Button(onClick = {
                val i = Intent(ctx, ScanService::class.java)
                if (running || st.status == ScanState.Status.BLOCKED) ctx.stopService(i)
                else ContextCompat.startForegroundService(ctx, i)
            }) {
                Text(if (running || st.status == ScanState.Status.BLOCKED) "STOP" else "START")
            }
        }
    }
}

@Composable
fun NeighborhoodScreen(vm: BluehoodViewModel) {
    val clusters by vm.clusters.collectAsState(emptyList())
    LazyColumn(Modifier.fillMaxSize().padding(12.dp)) {
        item { Text("近隣に漏れているデバイス (${clusters.size})", style = MaterialTheme.typography.titleMedium) }
        items(clusters) { c ->
            val title = c.label ?: c.name ?: c.trackerType ?: c.vendor ?: c.stableKey.take(12)
            val sub = buildString {
                c.vendor?.let { if (c.name != null) append(it) }        // 名前があるときだけベンダーを併記
                c.deviceType?.let { if (isNotEmpty()) append(" · "); append(it) }
                if (c.untrackable && c.trackerType == null) { if (isNotEmpty()) append(" · "); append("匿名化") }
                if (c.mine) { if (isNotEmpty()) append(" · "); append("自分の機器(判定から除外)") }
                if (isEmpty()) append("score ${"%.1f".format(c.stalkerScore)}")
            }
            ListItem(
                headlineContent = { Text(title) },
                supportingContent = { Text(sub) },
                // 自分のイヤホン/IQOS等は全ての場所に付いてくるため、除外できないと
                // 本物の警告がそれらに埋もれる。持ち主にしか分からないので申告制にする。
                trailingContent = {
                    FilterChip(
                        selected = c.mine,
                        onClick = { vm.setMine(c.id, !c.mine) },
                        label = { Text(if (c.mine) "自分の" else "自分の?") }
                    )
                }
            )
            HorizontalDivider()
        }
    }
}

@Composable
fun StalkerScreen(vm: BluehoodViewModel) {
    val hits by vm.stalkerCandidates.collectAsState(emptyList())
    val trackers by vm.trackers.collectAsState(emptyList())
    val st by ScanState.state.collectAsState()
    LazyColumn(Modifier.fillMaxSize().padding(12.dp)) {
        // 「判定が動いていない」ことを「該当なし」と絶対に同じ文言にしない
        if (st.status != ScanState.Status.SCANNING) item {
            Card(Modifier.fillMaxWidth().padding(bottom = 12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Text("スキャンが動作していないため、この画面の判定は最新ではありません。",
                    Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
            }
        } else if (st.locatedRatio in 0f..0.01f && st.wifiFpAps < PlaceFingerprint.MIN_APS) item {
            // 座標もWiFi指紋も無いときだけ「判定できない」と言う。
            // WiFi指紋があれば屋内でも場所は数えられるので、
            // 動いているのに「動作していません」と表示するのは逆向きの嘘になる。
            Card(Modifier.fillMaxWidth().padding(bottom = 12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Text("位置もWiFiの電波環境も取得できていないため、尾行判定は動作していません" +
                    "(屋外/窓際で測位するか、WiFiをONにしてください)。",
                    Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
            }
        } else if (st.locatedRatio in 0f..0.01f) item {
            // 判定は動くが、根拠がGPSではなく電波環境であることは隠さない。
            Card(Modifier.fillMaxWidth().padding(bottom = 12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Text("GPSは取得できていませんが、周囲のAP${st.wifiFpAps}局を場所の指紋として" +
                    "尾行判定は動作しています(証拠には座標が付きません)。",
                    Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
            }
        }

        item { Text("尾行の可能性 (score≥5)", style = MaterialTheme.typography.titleMedium) }
        if (hits.isEmpty()) item { Text("該当なし。複数地点で付いてくるデバイスはありません。") }
        items(hits) { c ->
            ListItem(
                headlineContent = { Text("⚠ ${c.stableKey.take(12)}") },
                supportingContent = { Text("score ${"%.1f".format(c.stalkerScore)}  最終 ${formatJst(c.lastSeen)}") },
                trailingContent = { Button(onClick = { vm.export(c.id) }) { Text("証拠化") } }
            )
            HorizontalDivider()
        }

        // 種別ごとに集約(同一種別の複数ローテーション窓を1行に。scoreは窓またぎ追尾判定の結果)
        val byType = trackers.filter { it.trackerType != null }
            .groupBy { it.trackerType!! }
            .map { (type, list) -> type to list.maxByOrNull { it.lastSeen }!! }
            .sortedByDescending { it.second.stalkerScore }
        item {
            Spacer(Modifier.height(16.dp))
            Text("検出トラッカー・匿名化対抗 (${byType.size}種)", style = MaterialTheme.typography.titleMedium)
            // 追尾とは「自分が動いたのに付いてくる」こと。自分が動いていなければ、
            // 付いてきたのか元からそこに在るのかを電波から区別する方法は無い。
            // ここを書かないと「追尾兆候なし」が「安全」と読まれてしまう。
            Text(
                "追尾判定には自分が200m以上移動している必要があります。"
                    + "同じ場所に留まっている間は、付いてきたのか元からそこに在るのかを区別できません。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
            )
        }
        if (byType.isEmpty()) item {
            Text("近くにトラッカー型(AirTag/Tile/SmartTag等)は検出されていません。")
        }
        items(byType) { (type, t) ->
            val following = t.stalkerScore >= 5.0    // 至近＋連続＋複数地点を満たしたものだけ
            ListItem(
                headlineContent = { Text("${if (following) "⚠ " else ""}$type") },
                supportingContent = {
                    Text(
                        if (following) "至近で追尾の兆候 (score ${"%.0f".format(t.stalkerScore)})  最終 ${formatJst(t.lastSeen)}"
                        else "検出のみ・追尾兆候なし  最終 ${formatJst(t.lastSeen)}"
                    )
                },
                trailingContent = {
                    if (following) Button(onClick = { vm.export(t.id) }) { Text("証拠化") }
                }
            )
            HorizontalDivider()
        }
    }
}

@Composable
fun EvidenceScreen(vm: BluehoodViewModel) {
    val json by vm.lastExport.collectAsState()
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text("証拠パッケージ (自分中心ログ・相手座標なし)", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        if (json == null) {
            Text("尾行アラート画面で「証拠化」を押すとここに出力されます。")
        } else {
            val obj = remember(json) { runCatching { org.json.JSONObject(json!!) }.getOrNull() }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    obj?.optString("tracker_type")?.takeIf { it.isNotEmpty() && it != "null" }
                        ?.let { EvidenceRow("種別", it) }
                    EvidenceRow("スコア", "%.1f".format(obj?.optDouble("stalker_score") ?: 0.0))
                    EvidenceRow("初観測", obj?.optString("first_seen_jst") ?: "-")
                    EvidenceRow("最終観測", obj?.optString("last_seen_jst") ?: "-")
                    EvidenceRow("観測回数", "${obj?.optInt("observation_count") ?: 0}回")
                    EvidenceRow("指紋(先頭)", obj?.optString("fingerprint_key")?.take(16)?.plus("…") ?: "-")
                }
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, json)
                        putExtra(Intent.EXTRA_SUBJECT, "Bluehood 証拠パッケージ")
                    }
                    ctx.startActivity(Intent.createChooser(intent, "証拠を保存・共有"))
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("保存・共有 (JSON)") }
            var showRaw by remember { mutableStateOf(false) }
            TextButton(onClick = { showRaw = !showRaw }, modifier = Modifier.fillMaxWidth()) {
                Text(if (showRaw) "▲ 生JSONを閉じる" else "▼ 生JSONを表示")
            }
            if (showRaw) {
                Box(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    Text(json!!, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

@Composable
private fun EvidenceRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            label, Modifier.width(80.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

/** 情報・支援。寄付先はTRC20(Tron)固定。 */
@Composable
fun SupportScreen() {
    val clip = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Text("Bluehood Scanner", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(4.dp))
        Text(
            "近隣のBLE広告を受動的に観測し、複数の独立した場所・時間で繰り返し現れる端末や、"
                + "AirTag等のトラッカーを検知します。通信は一切行わず、全ての解析は端末内で完結します。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(20.dp))
        Text("設計上の線引き", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        listOf(
            "相手の座標は保存しません(記録されるのは自分のGPSのみ)",
            "個人の特定・追跡は行いません(トラッカーは種別のみ判定)",
            "証拠はハッシュチェーン付きで、提出先は警察が正しい経路です",
        ).forEach {
            Text("・$it", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Spacer(Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(Modifier.height(20.dp))

        Text("Support", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            "開発の継続を支援いただける場合は、以下へお願いします。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("USDT / TRC20 (Tron)", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(8.dp))
                Text(
                    TRC20_ADDRESS,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { clip.setText(AnnotatedString(TRC20_ADDRESS)); copied = true },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (copied) "コピーしました" else "アドレスをコピー") }
                Spacer(Modifier.height(10.dp))
                Text(
                    "⚠ TRC20(Tronネットワーク)専用です。他のネットワークで送金すると資産は失われます。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** 地図: 位置が取れた観測を自分中心のウォードライブ点群として表示(OpenStreetMap)。 */
@Composable
fun MapScreen(vm: BluehoodViewModel) {
    val points by vm.located.collectAsState(emptyList())
    val ctx = LocalContext.current
    val map = remember {
        // URLテンプレートを明示したタイルソース(MAPNIKはgetTileURLStringが空を返し即失敗する端末があった)。
        // CartoのpermissiveなラスタタイルをHTTPS直指定。末尾は {z}/{x}/{y}.png が自動付与される。
        val tileSource = XYTileSource(
            "CartoLight", 0, 20, 256, ".png",
            arrayOf(
                "https://a.basemaps.cartocdn.com/light_all/",
                "https://b.basemaps.cartocdn.com/light_all/",
                "https://c.basemaps.cartocdn.com/light_all/"
            )
        )
        // 既定のMapView(MapTileProviderBasic)を使う。SQLキャッシュの読み書きと
        // タイル到着時のMapView再描画が正しく配線されている。
        MapView(ctx).apply {
            setTileSource(tileSource)
            setMultiTouchControls(true)
            setUseDataConnection(true)
            controller.setZoom(16.0)
        }
    }
    DisposableEffect(Unit) {
        map.onResume()
        onDispose { map.onPause(); map.onDetach() }
    }
    var status by remember { mutableStateOf<String?>(null) }
    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { map }, modifier = Modifier.fillMaxSize(), update = { mv ->
            mv.overlays.clear()
            for (o in points) {
                val la = o.myLat ?: continue
                val lo = o.myLon ?: continue
                val marker = Marker(mv)
                marker.position = GeoPoint(la, lo)
                marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                marker.title = "${o.mac ?: o.rawPayloadHex.take(8)}  rssi=${o.rssi}"
                mv.overlays.add(marker)
            }
            points.firstOrNull()?.let { first ->
                val la = first.myLat; val lo = first.myLon
                if (la != null && lo != null) mv.controller.setCenter(GeoPoint(la, lo))
            }
            mv.invalidate()
        })
        // 表示中の範囲のタイルを事前ダウンロード(オンライン時に実行→cache.dbに保存→オフラインで表示)
        Button(
            onClick = {
                val bbox = map.boundingBox
                val zMin = map.zoomLevelDouble.toInt()
                val zMax = (zMin + 2).coerceAtMost(19)
                val cm = CacheManager(map)
                val possible = try { cm.possibleTilesInArea(bbox, zMin, zMax) } catch (e: Exception) { -1 }
                if (possible > 6000) {
                    status = "範囲が広すぎます（$possible 枚）。ズームインして絞ってください。"
                } else {
                    cm.downloadAreaAsync(ctx, bbox, zMin, zMax, object : CacheManager.CacheManagerCallback {
                        override fun downloadStarted() { status = "ダウンロード開始…" }
                        override fun setPossibleTilesInArea(total: Int) { status = "対象 $total 枚を保存中…" }
                        override fun updateProgress(progress: Int, z: Int, zn: Int, zx: Int) { status = "保存中 $progress%（z$z）" }
                        override fun onTaskComplete() { status = "オフライン保存完了（z$zMin–$zMax）" }
                        override fun onTaskFailed(errors: Int) { status = "完了（失敗 $errors 枚）" }
                    })
                }
            },
            modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)
        ) { Text("この範囲を保存") }

        status?.let {
            Surface(
                color = MaterialTheme.colorScheme.inverseSurface,
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp)
            ) {
                Text(
                    it, Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    color = MaterialTheme.colorScheme.inverseOnSurface
                )
            }
        }

        if (points.isEmpty()) {
            Text(
                "位置付き観測がまだありません。GPSが測位すると点が出ます(屋外/窓際で測位)。",
                Modifier.align(Alignment.Center).padding(24.dp)
            )
        }
    }
}
