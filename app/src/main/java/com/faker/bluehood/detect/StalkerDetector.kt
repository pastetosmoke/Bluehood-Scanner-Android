package com.faker.bluehood.detect

import com.faker.bluehood.data.Observation
import kotlin.math.*

/** 私が滞在した「独立した文脈」。200m以上かつ30分以上で分離したものだけを1文脈とみなす。 */
data class Context(val lat: Double, val lon: Double, val startTs: Long, val endTs: Long)

object StalkerDetector {

    /**
     * 尾行スコア。1回の同乗ではなく「別々の場所・別々の時間での再出現」を重く見る。
     * - 自分の持ち物/隣家の固定機器 → 1文脈しか出ない(静止) → 除外
     * - 電車の同乗者 → 連続1トリップ → 非連続文脈ゼロ → 沈む
     * - 自宅+職場だけの同僚の機器 → 2地点は無加点なので沈む
     * - 4場所以上 + 日をまたぐ = 高確信
     */
    fun score(observations: List<Observation>, myContexts: List<Context>): Double {
        if (observations.isEmpty() || myContexts.isEmpty()) return 0.0

        val hits = myContexts.filter { ctx ->
            observations.any {
                val la = it.myLat; val lo = it.myLon
                la != null && lo != null &&
                    it.timestamp in ctx.startTs..ctx.endTs &&
                    distanceM(la, lo, ctx.lat, ctx.lon) < 50.0
            }
        }
        // 地点数は距離で数える。geohash のセル数で数えてはいけない —
        // セル境界は距離と無関係な位置にあり、同じ場所に留まっていても複数セルにまたがる。
        // 実機では103m四方から出ていないのに geohash7 で3セルに割れ、誤検知の原因になった。
        val distinctPlaces = TrackerFollowDetector.distinctPlaces(hits.map { it.lat to it.lon })
        // 2地点ではホーム+職場の偶然一致と区別できない。3地点以上かつ観測数5以上で初めてスコアを出す。
        if (distinctPlaces < 3) return 0.0
        if (observations.size < 5) return 0.0

        val nonContiguous = hits.sortedBy { it.startTs }
            .zipWithNext().count { (a, b) -> b.startTs - a.endTs > 30 * 60_000L }
        val spansDays =
            (hits.maxOf { it.endTs } - hits.minOf { it.startTs }) > 12 * 3600_000L

        // 最初の2地点は加点しない(生活圏の重なりは尾行の証拠にならない)。
        // 3地点目以降の広がり・時間的な非連続・日またぎが揃って初めて閾値5を超える。
        return ((distinctPlaces - 2) * 2.0 + nonContiguous * 1.5 + if (spansDays) 3.0 else 0.0)
            .coerceAtMost(10.0)
    }

    /** 観測列から独立文脈を切り出す(空間200m / 時間30分で分割)。 */
    fun buildContexts(myTrack: List<Triple<Double, Double, Long>>): List<Context> {
        val sorted = myTrack.sortedBy { it.third }
        val out = mutableListOf<Context>()
        var lat = 0.0; var lon = 0.0; var start = 0L; var end = 0L; var n = 0
        for ((la, lo, ts) in sorted) {
            if (n == 0) { lat = la; lon = lo; start = ts; end = ts; n = 1; continue }
            val far = distanceM(lat / n, lon / n, la, lo) > 200.0
            val gap = ts - end > 30 * 60_000L
            if (far || gap) {
                out += Context(lat / n, lon / n, start, end)
                lat = la; lon = lo; start = ts; end = ts; n = 1
            } else {
                lat += la; lon += lo; end = ts; n++
            }
        }
        if (n > 0) out += Context(lat / n, lon / n, start, end)
        return out
    }

    fun distanceM(la1: Double, lo1: Double, la2: Double, lo2: Double): Double {
        val r = 6_371_000.0
        val dLa = Math.toRadians(la2 - la1); val dLo = Math.toRadians(lo2 - lo1)
        val a = sin(dLa / 2).pow(2) +
            cos(Math.toRadians(la1)) * cos(Math.toRadians(la2)) * sin(dLo / 2).pow(2)
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    private const val B32 = "0123456789bcdefghjkmnpqrstuvwxyz"
    fun geohash(lat: Double, lon: Double, prec: Int): String {
        var latR = doubleArrayOf(-90.0, 90.0); var lonR = doubleArrayOf(-180.0, 180.0)
        val gh = StringBuilder(); var even = true; var bit = 0; var ch = 0
        while (gh.length < prec) {
            val range = if (even) lonR else latR; val v = if (even) lon else lat
            val mid = (range[0] + range[1]) / 2
            if (v >= mid) { ch = ch or (1 shl (4 - bit)); range[0] = mid } else range[1] = mid
            even = !even
            if (bit < 4) bit++ else { gh.append(B32[ch]); bit = 0; ch = 0 }
        }
        return gh.toString()
    }
}
