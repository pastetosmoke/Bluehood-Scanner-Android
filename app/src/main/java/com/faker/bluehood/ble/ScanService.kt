package com.faker.bluehood.ble

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.faker.bluehood.data.*
import com.faker.bluehood.detect.DeviceNamer
import com.faker.bluehood.detect.StalkerDetector
import com.faker.bluehood.detect.TrackerClassifier
import com.faker.bluehood.detect.TrackerFollowDetector
import com.faker.bluehood.detect.TrackerType
import kotlinx.coroutines.*

/**
 * 場所の指紋に採用するAPの下限RSSI。ESP32版の実測値に合わせる。
 * -85 だと明滅する局が混ざり、その場に居るのに一致率が落ちて誤って場所が増えた
 * (ESP32実機 2026-08-09)。強い局だけを使うほうが、少数でも安定する。
 */
private const val WIFI_RSSI_MIN = -78

/**
 * 前景サービス。GrapheneOS の落とし穴に対処する:
 *  - 画面OFFでスキャンが止まる → 前景サービス常駐(通知必須) + 空ScanFilter
 *  - Bluetooth自動オフタイマー → 状態変化を購読し、復帰時に自動で再武装
 *  - 30秒に5回の startScan 制限(AOSP) → 多重起動をフラグで抑止
 *
 * 設計方針: 「スキャンできていない」ことを絶対に隠さない。
 * BT OFF 時は bluetoothLeScanner が非nullを返し startScan も例外を出さないため、
 * 何もしないと通知が「スキャン中」と嘘をつき続ける(実機で確認済み)。
 * よって前提条件を毎回検証し、結果レートも監視して ScanState に反映する。
 */
@SuppressLint("MissingPermission")
class ScanService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var db: AppDatabase
    private val lastSeenKey = HashMap<String, Long>()   // 間引き用: key -> 最終保存時刻

    private var scanner: BluetoothLeScanner? = null
    @Volatile private var scanning = false             // 多重 startScan 防止(制限に当たると無音で失敗する)
    @Volatile private var results = 0L
    @Volatile private var lastResultAt = 0L
    @Volatile private var savedTotal = 0L
    @Volatile private var savedLocated = 0L
    @Volatile private var lastStatePush = 0L

    // 直近の位置fix。getLastKnownLocationは屋内でnullになるので、能動的に更新して保持する。
    // 古い fix を使い回すと全観測が同じ座標になり尾行判定が死ぬので、取得時刻を持たせる。
    @Volatile private var lastFix: FloatArrayLoc? = null
    private val locListener = android.location.LocationListener { l ->
        lastFix = FloatArrayLoc(l.latitude, l.longitude, l.accuracy, System.currentTimeMillis())
    }

    /** BT の ON/OFF を捉えて、止まったら正直に伝え、戻ったら自動で再武装する。 */
    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            if (i?.action == BluetoothAdapter.ACTION_STATE_CHANGED) evaluateAndScan()
        }
    }

    override fun onCreate() {
        super.onCreate()
        db = AppDatabase.get(this)                       // ViewModelと同一インスタンスを共有
        startForeground(1, notification("準備中…"))
        ContextCompat.registerReceiver(
            this, btReceiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED          // protected broadcast なので外部公開不要
        )
        startLocation()
        startWatchdog()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        evaluateAndScan()
        return START_STICKY
    }

    // --- 前提条件の検証 ---

    /**
     * スキャンが実際に成立する条件を全て検証する。欠けていれば理由を返す(nullなら準備OK)。
     * BT OFF は STATE_OFF ではなく BLE_ON になり scanner が非nullで返るため、
     * isEnabled(STATE_ON のときだけ true)で判定するのが唯一確実。
     */
    private fun blockingReason(): String? {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
            != PackageManager.PERMISSION_GRANTED
        ) return "Bluetooth権限がありません"

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) return "正確な位置情報の権限が必要です(おおよそでは不可)"

        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
            ?: return "Bluetoothが利用できません"
        if (!adapter.isEnabled) return "Bluetoothがオフです"

        val lm = getSystemService(LocationManager::class.java)
        if (lm?.isLocationEnabled != true) return "位置情報サービスがオフです"

        return null
    }

    /** 条件を確かめてからスキャンを開始/停止する。状態変化のたびに呼ばれる。 */
    private fun evaluateAndScan() {
        val reason = blockingReason()
        if (reason != null) {
            stopScanning()
            ScanState.blocked(reason)
            notifyState("停止中: $reason")
            return
        }
        startScanning()
    }

    private fun startScanning() {
        if (scanning) return                             // 30秒5回制限に当たると無音で失敗するので抑止
        val s = getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner
        if (s == null) { ScanState.blocked("スキャナを取得できません"); return }
        scanner = s
        // 既定(レガシー)スキャンが最も互換性が高く、周囲のレガシー広告を確実に拾う。
        // setLegacy(false)+PHY_ALL はコントローラ非対応時にエラーも返さず0件になるため使わない。
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        // 空のScanFilterを1個渡す = 全デバイスにマッチしつつ「フィルタ付きスキャン」扱いになり、
        // 画面OFF中も結果が配信される(フィルタ無しは画面OFFで無音停止するため)。
        val filters = listOf(ScanFilter.Builder().build())
        try {
            s.startScan(filters, settings, callback)
            scanning = true
            lastResultAt = System.currentTimeMillis()    // 猶予の起点(即STALLED判定を避ける)
            ScanState.scanning()
            notifyState("スキャン中… 近隣のBLE/BTを監視しています")
        } catch (e: Exception) {
            ScanState.blocked("スキャン開始に失敗: ${e.javaClass.simpleName}")
            notifyState("停止中: スキャン開始に失敗しました")
        }
    }

    private fun stopScanning() {
        if (!scanning) return
        // BT が OFF になった後の stopScan は例外を投げうるので必ず包む
        try { scanner?.stopScan(callback) } catch (_: Exception) {}
        scanning = false
    }

    /**
     * 結果レートの監視。無音故障(BT断・位置OFF・スキャン制限・Doze絞り)は
     * どれも「通知は正常・結果0件」という同じ症状になるため、これが共通の受け皿になる。
     */
    private fun startWatchdog() = scope.launch {
        while (isActive) {
            delay(30_000L)
            val st = ScanState.state.value
            if (st.status == ScanState.Status.STOPPED) continue
            val reason = blockingReason()
            if (reason != null) {                        // 稼働中に前提が崩れた場合を拾う
                stopScanning(); ScanState.blocked(reason); notifyState("停止中: $reason")
                continue
            }
            val silentMs = System.currentTimeMillis() - lastResultAt
            if (scanning && silentMs > 120_000L) {
                // 市街地ならBLE広告は数秒で来る。2分の沈黙は異常とみなして再武装を試みる。
                ScanState.update { it.copy(status = ScanState.Status.STALLED, reason = "受信が2分以上途絶えています") }
                notifyState("⚠ 受信が途絶えています。再接続を試行中")
                stopScanning(); startScanning()
            }
            ScanState.update {
                it.copy(
                    results = results, lastResultAt = lastResultAt,
                    locatedRatio = if (savedTotal == 0L) -1f else savedLocated.toFloat() / savedTotal
                )
            }
        }
    }

    private fun startLocation() {
        val lm = getSystemService(LocationManager::class.java)
        val now = System.currentTimeMillis()
        lastFix = (lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER))
            ?.let { FloatArrayLoc(it.latitude, it.longitude, it.accuracy, now) }
        // 位置未取得でもスキャン結果は捨てず、fixが来たら以降の観測に座標が乗る
        try { lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 10_000L, 0f, locListener) } catch (_: Exception) {}
        try { lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 10_000L, 0f, locListener) } catch (_: Exception) {}
    }

    /** 古すぎる fix は使わない。使い回すと全観測が同じ座標になり尾行判定が無効化される。 */
    private fun freshFix(): FloatArrayLoc? =
        lastFix?.takeIf { System.currentTimeMillis() - it.at < 5 * 60_000L }

    // --- WiFi指紋(場所の同定) ---

    @Volatile private var wifiFpCache: String? = null
    @Volatile private var wifiFpAt = 0L
    private val wifiSalt: ByteArray by lazy {
        val sp = getSharedPreferences("bluehood", Context.MODE_PRIVATE)
        val hex = sp.getString("wifi_salt", null) ?: run {
            val b = ByteArray(16); java.security.SecureRandom().nextBytes(b)
            val h = b.joinToString("") { "%02x".format(it) }
            sp.edit().putString("wifi_salt", h).apply(); h
        }
        hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    /**
     * 周囲のAPの集合を場所の指紋にする。GPSが取れない屋内でも場所を数えられるのが要点。
     *
     * **startScan() は呼ばない。** アクティブスキャンはプローブ要求を送信し、そこに自分のMACが載る。
     * 対監視の道具が自分の足跡を撒いていたら本末転倒なので、
     * OSが自分の用途で既に集めたキャッシュ(scanResults)を読むだけにする。
     * 副次的に Android 9以降のスキャン頻度制限とも無縁になる。
     *
     * BSSIDは生で保存しない。公開のジオロケーションDBで座標に復元できてしまうため、
     * 端末固有ソルト付きのハッシュにする。集合の一致度計算はハッシュのままでも成立する。
     */
    private fun wifiFingerprint(): String? {
        val now = System.currentTimeMillis()
        wifiFpCache?.let { if (now - wifiFpAt < 30_000L) return it }
        val wm = getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager ?: return null
        val results = try { wm.scanResults } catch (e: SecurityException) { null } ?: return null
        if (results.isEmpty()) return null
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val fp = results
            .filter { it.level >= WIFI_RSSI_MIN }      // 弱い局は明滅して指紋を汚す
            .mapNotNull { it.BSSID }
            .distinct()
            .map { bssid ->
                md.reset(); md.update(wifiSalt)
                md.digest(bssid.toByteArray()).take(6).joinToString("") { "%02x".format(it) }
            }
            .sorted()
        wifiFpAt = now
        wifiFpCache = if (fp.isEmpty()) null else fp.joinToString(",")
        // 「取れていない」ことを隠さない。GrapheneOSでは権限やWiFiのOFFで
        // scanResults が黙って空を返すことがあり、その場合 wifiFp は付かないまま
        // 屋内判定が座標頼みに戻る(=以前の穴に落ちる)。件数を必ず残す。
        android.util.Log.i("Bluehood", "wifiFp: scanResults=${results.size} 採用=${fp.size}")
        ScanState.update { it.copy(wifiFpAps = fp.size) }
        return wifiFpCache
    }

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            results++
            val t = System.currentTimeMillis()
            lastResultAt = t
            // 受信件数は生存の証拠なので即座にUIへ反映する。ただし毎件だと過剰なので1秒に間引く。
            if (t - lastStatePush > 1_000L) {
                lastStatePush = t
                ScanState.update {
                    it.copy(
                        status = ScanState.Status.SCANNING, reason = null,
                        results = results, lastResultAt = t,
                        locatedRatio = if (savedTotal == 0L) -1f else savedLocated.toFloat() / savedTotal
                    )
                }
            }
            val fp = BleFingerprint.from(result, advIntervalMs = null)
            // 探索は更新頻度が使い勝手を決めるので、handle() 側の10秒間引きより前に食わせる。
            HuntState.record(result, fp)
            handle(fp, result, freshFix())     // 位置は付けられれば付ける。無くても近隣一覧には出す。
        }
        override fun onScanFailed(errorCode: Int) {
            scanning = false
            if (errorCode == SCAN_FAILED_ALREADY_STARTED) { scanning = true; return }  // 実害なし
            val why = when (errorCode) {
                SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "スキャン登録に失敗(頻度制限の可能性)"
                SCAN_FAILED_FEATURE_UNSUPPORTED -> "この端末は該当スキャンに非対応"
                SCAN_FAILED_INTERNAL_ERROR -> "Bluetoothスタックの内部エラー"
                else -> "スキャン失敗(code=$errorCode)"
            }
            ScanState.blocked(why)
            notifyState("停止中: $why")
        }
    }

    private fun handle(fp: BleFingerprint, result: ScanResult, loc: FloatArrayLoc?) {
        val tracker = TrackerClassifier.classify(result)
        val idn = DeviceNamer.identify(result)
        // 匿名化トラッカーは捨てず、種別＋ペイロード安定部でウィンドウ内個体として拾う
        val key = if (tracker != null) trackerKey(tracker, result) else fp.stableKey()
        val now = System.currentTimeMillis()
        // 同一キーは10秒に1回だけ保存(間引き)
        if (now - (lastSeenKey[key] ?: 0L) < 10_000L) return
        lastSeenKey[key] = now

        scope.launch {
            val dao = db.dao()
            val existing = dao.findByKey(key)
            val clusterId = if (existing == null) {
                dao.insertCluster(
                    DeviceCluster(
                        stableKey = key, firstSeen = now, lastSeen = now,
                        untrackable = fp.isLowEntropy && tracker == null,
                        trackerType = tracker?.label,
                        name = idn.name, vendor = idn.vendor, deviceType = idn.type
                    )
                ).let { if (it == -1L) dao.findByKey(key)!!.id else it }
            } else {
                // 一度学習した名前は保持しつつ、後から分かった素性を補完
                dao.updateCluster(
                    existing.copy(
                        lastSeen = now,
                        name = existing.name ?: idn.name,
                        vendor = existing.vendor ?: idn.vendor,
                        deviceType = existing.deviceType ?: idn.type
                    )
                ); existing.id
            }

            dao.insertObservation(
                Observation(
                    clusterId = clusterId, timestamp = now, rssi = result.rssi,
                    mac = result.device?.address,
                    myLat = loc?.lat, myLon = loc?.lon, myAccuracyM = loc?.acc,
                    rawPayloadHex = result.scanRecord?.bytes?.joinToString("") { "%02x".format(it) } ?: "",
                    wifiFp = wifiFingerprint()
                )
            )
            savedTotal++
            if (loc != null) savedLocated++

            // トラッカーは窓またぎ追尾判定、通常デバイスは尾行スコア
            // 自分の持ち物と申告された機器はスコアを再計算しない。
            // 自分のイヤホン/IQOS等は定義上どこにでも付いてくるので、除外しないと必ず尾行判定に載る。
            if (tracker != null) recomputeTrackerFollow(tracker.label, clusterId)
            else if (!fp.isLowEntropy && existing?.mine != true) recomputeScore(clusterId)
        }
    }

    /** 種別ごとに全観測を横断し、至近＋連続＋複数地点で追尾を判定(ローテーションをまたぐ)。 */
    private suspend fun recomputeTrackerFollow(typeLabel: String, clusterId: Long) {
        val dao = db.dao()
        val follow = TrackerFollowDetector.assess(dao.trackerObservations(typeLabel))
        val score = TrackerFollowDetector.score(follow)
        // 判定は種別単位なので、書き込みも種別単位で行う。
        // 観測中のクラスタだけ更新すると、ローテーションで二度と現れない古いクラスタに
        // 古いスコアが取り残され、実機では誤警告が36件も残り続けた。
        dao.updateTrackerScores(typeLabel, score)
        if (follow.following) {
            alert("⚠ トラッカー追尾の可能性: $typeLabel が至近で ${follow.closePlaces}地点 / ${follow.longestMin}分")
        }
    }

    /** トラッカーの個体をウィンドウ内で同定するキー。先頭(type/len)と末尾(status/hint)を除いた安定部を使う。 */
    private fun trackerKey(t: TrackerType, r: ScanResult): String {
        val bytes = r.scanRecord?.bytes ?: ByteArray(0)
        val stable = if (bytes.size > 4) bytes.copyOfRange(2, bytes.size - 1) else bytes
        return sha256("TRACKER:${t.name}:" + stable.joinToString("") { "%02x".format(it) })
    }

    private fun sha256(s: String): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private suspend fun recomputeScore(clusterId: Long) {
        val dao = db.dao()
        val obs = dao.observationsFor(clusterId)
        // 座標が無い観測も渡す。WiFi指紋があれば屋内でも場所として数えられる。
        // 以前は座標のある観測だけを渡しており、GPSが取れない環境では
        // 近隣一覧が埋まるのに判定は永久に0.0(=安全と誤認)になった。
        val score = StalkerDetector.score(obs)
        val cluster = dao.findById(clusterId) ?: return
        if (score != cluster.stalkerScore) dao.updateCluster(cluster.copy(stalkerScore = score))
        if (score >= 5.0) alert("⚠ 尾行の可能性: 複数地点で同一デバイスを検知(score=$score)")
    }

    // --- 位置(自分の座標のみ) ---
    data class FloatArrayLoc(val lat: Double, val lon: Double, val acc: Float, val at: Long)

    // --- 通知 ---
    private fun channel(): String {
        val ch = "scan"
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(ch, "Bluehood", NotificationManager.IMPORTANCE_LOW)
        )
        return ch
    }

    private fun notification(text: String): Notification =
        NotificationCompat.Builder(this, channel())
            .setContentTitle("Bluehood").setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass).setOngoing(true).build()

    /** 前景通知そのものを書き換える(常駐通知が状態を正しく示すようにする)。 */
    private fun notifyState(text: String) =
        getSystemService(NotificationManager::class.java).notify(1, notification(text))

    /** 脅威の警告は別ID。常駐通知を上書きしないようにする。 */
    private fun alert(text: String) {
        val n = NotificationCompat.Builder(this, channel())
            .setContentTitle("Bluehood").setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass).build()
        getSystemService(NotificationManager::class.java).notify(2, n)
    }

    override fun onDestroy() {
        stopScanning()
        scope.cancel()
        try { unregisterReceiver(btReceiver) } catch (_: Exception) {}
        try { getSystemService(LocationManager::class.java).removeUpdates(locListener) } catch (_: Exception) {}
        ScanState.stopped()
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
