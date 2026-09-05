package com.faker.bluehood.detect

import com.faker.bluehood.data.Observation
import kotlin.math.*

object StalkerDetector {

    /** 独立した文脈とみなす時間の隔たり。これ未満の連続は1回の同行として数える。 */
    const val SESSION_GAP_MS = 30 * 60_000L

    /**
     * 尾行スコア。1回の同乗ではなく「別々の場所・別々の時間での再出現」を重く見る。
     * - 自分の持ち物/隣家の固定機器 → 1文脈しか出ない(静止) → 除外
     * - 電車の同乗者 → 連続1トリップ → 非連続文脈ゼロ → 沈む
     * - 自宅+職場だけの同僚の機器 → 2地点は無加点なので沈む
     * - 4場所以上 + 日をまたぐ = 高確信
     *
     * 場所の同定は [PlaceFingerprint] に委ねる。WiFi指紋を優先し、無ければ座標で束ねる。
     * 以前は座標だけで数えており、屋内GPSの揺れが103m四方を3地点に割って誤警告を出した。
     * また GPS が取れない観測は捨てられ、判定が永久に0.0のまま「安全」に見える穴があった。
     */
    fun score(observations: List<Observation>): Double {
        if (observations.isEmpty()) return 0.0

        val placeIds = PlaceFingerprint.assignPlaces(observations)
        val placed = observations.indices
            .filter { placeIds[it] >= 0 }
            .sortedBy { observations[it].timestamp }
        if (placed.isEmpty()) return 0.0

        val distinctPlaces = placed.map { placeIds[it] }.distinct().size
        // 2地点ではホーム+職場の偶然一致と区別できない。3地点以上かつ観測数5以上で初めてスコアを出す。
        if (distinctPlaces < 3) return 0.0
        if (placed.size < 5) return 0.0

        // 時間的に離れた再出現の回数。同一トリップ中の連続観測は1回として潰す。
        val nonContiguous = placed.zipWithNext()
            .count { (a, b) -> observations[b].timestamp - observations[a].timestamp > SESSION_GAP_MS }

        val spansDays =
            (observations[placed.last()].timestamp - observations[placed.first()].timestamp) >
                12 * 3600_000L

        // 最初の2地点は加点しない(生活圏の重なりは尾行の証拠にならない)。
        // 3地点目以降の広がり・時間的な非連続・日またぎが揃って初めて閾値5を超える。
        return ((distinctPlaces - 2) * 2.0 + nonContiguous * 1.5 + if (spansDays) 3.0 else 0.0)
            .coerceAtMost(10.0)
    }

    fun distanceM(la1: Double, lo1: Double, la2: Double, lo2: Double): Double {
        val r = 6_371_000.0
        val dLa = Math.toRadians(la2 - la1); val dLo = Math.toRadians(lo2 - lo1)
        val a = sin(dLa / 2).pow(2) +
            cos(Math.toRadians(la1)) * cos(Math.toRadians(la2)) * sin(dLo / 2).pow(2)
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }
}
