package com.faker.bluehood.ble

import android.bluetooth.le.ScanResult
import com.faker.bluehood.detect.TrackerClassifier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 「自分に付けられたタグを物理的に探す」ためのRSSI追跡(ホット/コールド探索)。
 *
 * 相手を追う機能ではない。対象は既に自分を追尾していると判定された機器であり、
 * 得られるのは自分の手元での電波強度だけなので、相手座標を持たない設計線引きは崩れない。
 * 状態は全て揮発。証拠化は既存の観測ログ側の責務で、ここでは一切保存しない。
 */
object HuntState {

    /**
     * 探索対象。トラッカーはMACも個体キーもローテーションするため、
     * 原則は種別(trackerType)で追う。固定MACの機器だけ stableKey で追える。
     */
    data class Target(
        val trackerType: String? = null,
        val stableKey: String? = null,
        val label: String,
    )

    data class Sample(val t: Long, val rssi: Int)

    enum class Trend { CLOSER, FARTHER, FLAT, UNKNOWN }

    data class State(
        val target: Target? = null,
        val samples: List<Sample> = emptyList(),
        val bestRssi: Int? = null,   // 探索開始以降の最接近。「さっきの方が近かった」を判断する基準
        val lastSeen: Long = 0,
    )

    /** これを超えて受信が無ければ「見失った」。無音を「遠い」と誤表示させないための境界。 */
    const val STALE_MS = 8_000L

    private const val RETAIN_MS = 20_000L     // 保持する履歴長

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun start(target: Target) {
        _state.value = State(target = target)
    }

    fun stop() {
        _state.value = State()
    }

    /**
     * スキャン結果を1件取り込む。探索中でなければ即座に抜けるので、非探索時のコストは無い。
     *
     * ScanService の保存側は同一キーを10秒に1回へ間引いているが、探索では更新頻度そのものが
     * 使い勝手を決めるため、こちらは間引かず全件を受ける。
     */
    @Synchronized
    fun record(result: ScanResult, fp: BleFingerprint) {
        val st = _state.value
        val target = st.target ?: return
        if (!matches(target, result, fp)) return

        val now = System.currentTimeMillis()
        val kept = (st.samples + Sample(now, result.rssi)).filter { now - it.t <= RETAIN_MS }
        _state.value = st.copy(
            samples = kept,
            bestRssi = maxOf(result.rssi, st.bestRssi ?: Int.MIN_VALUE),
            lastSeen = now,
        )
    }

    private fun matches(target: Target, result: ScanResult, fp: BleFingerprint): Boolean {
        target.trackerType?.let { return TrackerClassifier.classify(result)?.label == it }
        target.stableKey?.let { return fp.stableKey() == it }
        return false
    }

}

/** 直近の代表値。単発の外れ値に振られないよう中央値を使う。 */
fun HuntState.State.smoothedRssi(now: Long): Int? {
    val recent = samples.filter { now - it.t <= RECENT_MS }.map { it.rssi }
    return if (recent.isEmpty()) null else recent.sorted()[recent.size / 2]
}

/**
 * 近づいているか離れているか。
 * RSSIは反射と体の遮蔽で数dB平気で揺れるので、窓ごとの中央値差が閾値を超えたときだけ向きを出す。
 */
fun HuntState.State.trend(now: Long): HuntState.Trend {
    val recent = samples.filter { now - it.t <= RECENT_MS }.map { it.rssi }
    val prev = samples.filter { now - it.t in (RECENT_MS + 1)..PREV_MS }.map { it.rssi }
    if (recent.size < 2 || prev.size < 2) return HuntState.Trend.UNKNOWN
    val d = recent.sorted()[recent.size / 2] - prev.sorted()[prev.size / 2]
    return when {
        d >= TREND_DB -> HuntState.Trend.CLOSER
        d <= -TREND_DB -> HuntState.Trend.FARTHER
        else -> HuntState.Trend.FLAT
    }
}

/** 受信が途絶えている状態。「遠い」と区別して表示しなければならない。 */
fun HuntState.State.isStale(now: Long): Boolean =
    target != null && now - lastSeen > HuntState.STALE_MS

/**
 * 表示用の「熱さ」0..1。距離ではない。
 * RSSIから距離を出すのは環境依存が大きすぎて嘘になるので、相対的な強さだけを見せる。
 */
fun HuntState.State.heat(now: Long): Float? {
    val r = smoothedRssi(now) ?: return null
    return ((r + 100).coerceIn(0, 60)) / 60f    // -100dBm=0.0, -40dBm=1.0
}

private const val RECENT_MS = 2_500L    // 「今」とみなす窓
private const val PREV_MS = 6_000L      // 比較対象の「少し前」の窓の終端
private const val TREND_DB = 3          // これ未満の差は雑音とみなす(ヒステリシス)
